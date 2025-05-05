package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class Decode_IO(implicit p: Parameters) extends Bundle {
  // with IF
  val if_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new IF_ID_uop())))
  val id_ready = Output(Bool()) // ID是否准备好接收指令
  // with Rename
  val rename_uop = Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop()))
  val rename_ready = Input(Bool()) // Rename是否准备好接收指令
  // with ROB
  val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))) // ROB提交时的广播信号，发生误预测时对本模块进行冲刷
}





class DecodeUnit(implicit p: Parameters) extends Module {
  val io = IO(new Decode_IO())
  
  //是否接受指令
  val rob_flush = io.rob_commitsignal.map(_.valid)reduce(_||_)  //是否要flush
  io.id_ready = io.rename_ready && !rob_flush

  for (i <-0 until p.CORE_WIDTH){
    val valid = io.if_uop(i).valid && ready

    //分解IF来的指令
    val instr = io.if_uop(i).bits.instr
    val opcode = instr(6,0)
    val rd = instr(11,7)
    val funct3 = instr(14,12)
    val rs1 = instr(19,15)
    val rs2 = instr(24,20)
    val funct7(31,25)

    //读取可能的立即数
    val immI = instr(31,20)
    val immS = Cat(instr(31,25),instr(11,7))
    val immB = Cat(instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
    val immU = Cat(instr(31, 12), 0.U(12.W))
    val immJ = Cat(instr(31), instr(19,12), instr(20), instr(30,21), 0.U(1.W))

    //后续要使用的立即数
    val immExt = Wire(UInt(p.XLEN.W)) 
    immExt := 0.U

    
    val instr_type = Wire(InstrType())
    instr_type := InstrType.R


      //根据操作数分类
    switch(opcode){
      is("b0110011".U){
        instr_type := InstrType.R
        immExt := 0.U
      }

      is("b0010011".U){
        instr_type := InstrType.I
        immExt := Cat(Fill(20, immI(11)), immI)
      }

      is("b0000011".U) { //Load
        instr_type := InstrType.I
        immExt := Cat(Fill(20, immI(11)), immI)
      }

      is("b0100011".U) {
        instr_type := InstrType.S
        immExt := Cat(Fill(20, immS(11)), immS)
      }

      is("b1100011".U) {
        instr_type := InstrType.B
        immExt := Cat(Fill(19, immB(12)), immB)
      }

      is("b0110111".U) { //LUI
        instr_type := InstrType.U
        immExt := immU
      }

      is("b0010111".U) { //AUIPC
        instr_type := InstrType.U
        immExt := immU
      }

      is("b1101111".U) {
        instr_type := InstrType.J
        immExt := Cat(Fill(11, immJ(20)), immJ)
      }

      is("b1100111".U) {
        instr_type := InstrType.I
        immExt := Cat(Fill(20, immI(11)), immI)
      }
      
      is("b1110011".U) { //CSR
        instr_type := InstrType.I
        immExt := Cat(Fill(20, immI(11)), immI)
      }
      
    }//switch的后括号


    
    
    
    
    
  }//for的后括号

  



  
}
