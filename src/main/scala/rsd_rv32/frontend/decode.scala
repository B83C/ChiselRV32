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


//ID到RENAME的uop定义
class ID_RENAME_uop(implicit p:Parameters) extends Bundle{
  val pc = 


}




class DecodeUnit(implicit p: Parameters) extends Module {
  val io = IO(new Decode_IO())
  
  //是否接受指令
  val rob_flush = io.rob_commitsignal.map(_.valid)reduce(_||_)  //是否要flush
  io.id_ready = io.rename_ready && !rob_flush

  for (i <-0 until p.CORE_WIDTH{
    val if_uop = io.if_uop(i)
    val rename_uop = Wire(Valid(new ID_RENAME_uop()))
    
    
    )

  



  
}
