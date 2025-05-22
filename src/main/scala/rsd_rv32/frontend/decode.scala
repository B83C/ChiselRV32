package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class Decode_IO(implicit p: Parameters) extends CustomBundle {
  // with IF
  val id_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new IF_ID_uop())))
  val id_ready = Bool() // ID是否准备好接收指令
  // with Rename
  val rename_uop = Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop()))
  val rename_ready = Flipped(Bool()) // Rename是否准备好接收指令
  // with ROB
  val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))) // ROB提交时的广播信号，发生误预测时对本模块进行冲刷
}

//级间流水寄存器
class ID_Rename_Stage_reg(implicit  p: Parameters) extends CustomModule {
  val io = IO(new Bundle {
    val rob_flush = Flipped(Bool())
    val rename_ready = Flipped(Bool())
    val stage_reg_uop = Flipped(Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop())))
    val rename_uop = Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop()))
  })
  val uop = RegInit(Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop())), 0.U.asTypeOf(Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop()))))
  when (io.rob_flush){
    uop := 0.U.asTypeOf(Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop())))
  }.elsewhen (io.rename_ready){ //当rename ready时才允许更新
    uop := io.stage_reg_uop
  }.otherwise{
    uop := uop
  }
  io.rename_uop := uop

  //Debugging
  uop.foreach(x => {
    x.bits.debug := DontCare 
  })
  when(uop.map(_.valid).reduce(_ || _)) {
     for(i <- 0 until p.CORE_WIDTH) {
      printf(cf"[Decode->Rename] ")
    
      when(uop(i).valid) {
        val itype = uop(i).bits.instr_type
        val addr  = uop(i).bits.instr_addr 
        printf(cf" PC: ${addr} type: ${itype} ")
      }
      printf(cf"\n")
    }   
  }
}


class DecodeUnit(implicit p: Parameters) extends CustomModule {
  val io = IO(new Decode_IO())

  //是否接受指令
  //val rob_flush = io.rob_commitsignal.map(_.valid)reduce(_||_)  //是否要flush
  //flush信号
  val rob_flush = io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
  io.id_ready := io.rename_ready && !rob_flush
  //级间流水寄存器
  val stage_reg = Module(new ID_Rename_Stage_reg())
  stage_reg.io.rename_ready := io.rename_ready
  stage_reg.io.rob_flush := rob_flush
  io.rename_uop := stage_reg.io.rename_uop
  when(rob_flush) {
    stage_reg.io.stage_reg_uop := 0.U.asTypeOf(Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop())))
  }.otherwise {
    for (i <- 0 until p.CORE_WIDTH) {
      val instr = io.id_uop(i).bits.instr
      //传递已有变量
      stage_reg.io.stage_reg_uop(i).valid := io.id_uop(i).valid
      stage_reg.io.stage_reg_uop(i).bits.instr := instr(31, 7) //之前有规定是拼接吗
      stage_reg.io.stage_reg_uop(i).bits.instr_addr := io.id_uop(i).bits.instr_addr
      stage_reg.io.stage_reg_uop(i).bits.target_PC := io.id_uop(i).bits.target_PC
      stage_reg.io.stage_reg_uop(i).bits.GHR := io.id_uop(i).bits.GHR
      stage_reg.io.stage_reg_uop(i).bits.branch_pred := io.id_uop(i).bits.branch_pred
      stage_reg.io.stage_reg_uop(i).bits.btb_hit := io.id_uop(i).bits.btb_hit
      

      //切割instr
      val opcode = instr(6, 0)
      val rd = instr(11, 7)
      val func3 = instr(14, 12)
      val rs1 = instr(19, 15)
      val rs2 = instr(24, 20)
      val func7 = instr(31, 25)

      //区分InstrType
      val instr_type = WireDefault(InstrType(), InstrType.ALU)
      val opr1_sel = WireDefault(OprSel(), OprSel.Z)
      val opr2_sel = WireDefault(OprSel(), OprSel.Z)
      stage_reg.io.stage_reg_uop(i).bits.instr_type := instr_type
      stage_reg.io.stage_reg_uop(i).bits.fu_signals.opr1_sel := opr1_sel
      stage_reg.io.stage_reg_uop(i).bits.fu_signals.opr2_sel := opr2_sel
      switch(opcode) {

        is("b0110011".U) {
          when(func7 === "b0000001".U) {
            when(func3(2) === 0.U) {
              instr_type := InstrType.MUL
            }.elsewhen(func3(2) === 1.U) {
              instr_type := InstrType.DIV_REM
            }.otherwise {
              instr_type := InstrType.ALU
            }
          }.otherwise {
            instr_type := InstrType.ALU
          }
          //immExt := 0.U
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.REG
        }

        is("b0010011".U) { //REG-IMM-ALU
          instr_type := InstrType.ALU
          //immExt := Cat(Fill(20, immI(11)), immI)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.IMM
        }

        is("b0000011".U) { //Load
          instr_type := InstrType.LD
          //immExt := Cat(Fill(20, immI(11)), immI)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.IMM
        }

        is("b0100011".U) { //Store
          instr_type := InstrType.ST
          //immExt := Cat(Fill(20, immS(11)), immS)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.IMM
        }

        is("b1100011".U) { //Branch
          instr_type := InstrType.Branch
          //immExt := Cat(Fill(19, immB(12)), immB)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.REG
        }

        is("b1101111".U) { //JAL
          instr_type := InstrType.Jump
          //immExt := Cat(Fill(11, immJ(20)), immJ)
          opr1_sel := OprSel.PC
          opr2_sel := OprSel.IMM
        }

        is("b1100111".U) { //JALR
          instr_type := InstrType.Jump
          //immExt := Cat(Fill(20, immI(11)), immI)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.IMM
        }

        is("b1110011".U) { //CSR
          instr_type := InstrType.CSR
          //immExt := Cat(Fill(20, immI(11)), immI)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.IMM
        }

        is("b0110111".U) { //LUI
          instr_type := InstrType.ALU
          //immExt := immU
          opr1_sel := OprSel.IMM
          opr2_sel := OprSel.Z
        }

        is("b0010111".U) { //AUIPC
          instr_type := InstrType.ALU
          //immExt := immU
          opr1_sel := OprSel.PC
          opr2_sel := OprSel.IMM
        }

      } //switch的后括号

    }
  }
  
  // Debugging
  stage_reg.io.stage_reg_uop.foreach(x => {
    x.bits.debug := DontCare
  })
  io.rename_uop.zip(io.id_uop).foreach{case (x, y) => {
      x.bits.debug := RegNext(y.bits.debug)
  }}
}
//  for (i <-0 until p.CORE_WIDTH){
//    val valid = io.id_uop(i).valid && ready
//
//    //分解IF来的指令
//    val instr = io.id_uop(i).bits.instr
//    val opcode = instr(6,0)
//    val rd = instr(11,7)
//    val funct3 = instr(14,12)
//    val rs1 = instr(19,15)
//    val rs2 = instr(24,20)
//    // val funct7(31,25)
//
//    val instr_type = Wire(InstrType())
//    instr_type := InstrType.R
//
//    val fuSignals = Wire(new FUSignals())
//    fu_signals.opr1_sel := OprSel.REG
//    fu_signals.opr2_sel := OprSel.REG
//
//      //根据操作数分类
//
//
//    val instr_trimmed = Cat(funct3, funct7, rd, rs1, rs2,//immExt)
//
//    //传递给RENAME
//    val uop = Wire(new ID_RENAME_uop())
//    rename.uop.bits.instr := instr_trimmed
//    rename.uop.bits.instr_type := instr_type
//    rename.uop.bits.fu_signals := fuSignals
//
//    //if给的部分
//    rename.uop.bits.instr_addr := id_uop.instr_addr
//    rename_uop.bits.target_PC := id_uop.target_PC
//    rename_uop.bits.GHR := id_uop.GHR
//    rename_uop.bits.branch_pred := id_uop.branch_pred
//    rename_uop.bits.btb_hit := id_uop.btb_hit
//
//
//    //输出完成
//    io.rename_uop(i) :=rename_uop
//

//}for的后括号


