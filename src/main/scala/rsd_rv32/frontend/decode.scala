package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.scheduler._

class Decode_IO(implicit p: Parameters) extends CustomBundle {
  // 通过Decoupled向fetch施压，这样实现比较正确
  // 其中外部valid表示fetch是否正在发送
  // 内部的valid表示指令是否有效
  // 不同层级表示的意义有所不同
  // 多余的valid会被优化掉
  // from IF
  val id_uop = Flipped(Decoupled(Vec(p.CORE_WIDTH, Valid(new IF_ID_uop()))))
  // to Rename
  val rename_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop())))
  // with ROB
  val rob_controlsignal = Flipped(Valid(new ROBControlSignal)) //来自于ROB的控制信号
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
  val rob_flush = io.rob_controlsignal.valid && io.rob_controlsignal.bits.isMispredicted
  
  val input_ready = io.rename_uop.ready
  io.id_uop.ready := input_ready
  
  val output_valid = io.id_uop.valid && input_ready
  io.rename_uop.valid := RegNext(output_valid)

  io.id_uop.bits.zip(io.rename_uop.bits).foreach{ case (id, rename_out) => {
    val instr_valid = id.valid
    val to_rename = Wire(new ID_RENAME_uop) 
    (to_rename: Data).waiveAll :<>= (id.bits: Data).waiveAll

    // _后缀表示opcode被截
    to_rename.instr_ := id.bits.instr(31, 7)

    val dis = Instr.disassemble(id.bits.instr)
    val instr_type = WireDefault(InstrType(), InstrType.ALU)
    val opr1_sel = WireDefault(OprSel(), OprSel.Z)
    val opr2_sel = WireDefault(OprSel(), OprSel.Z)
    
    to_rename.instr_type := instr_type
    to_rename.fu_signals.opr1_sel := opr1_sel
    to_rename.fu_signals.opr2_sel := opr2_sel
   
    switch(dis.opcode) {
      is("b0110011".U) {
        when(dis.funct7 === "b0000001".U) {
          when(dis.funct3(2) === 0.U) {
            instr_type := InstrType.MUL
          }.elsewhen(dis.funct3(2) === 1.U) {
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
    }

    // TODO
    rename_out.valid := RegNext(instr_valid && output_valid)
    rename_out.bits := RegEnable(to_rename, instr_valid && output_valid)

    // Debugging
    rename_out.bits.debug(id)
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


