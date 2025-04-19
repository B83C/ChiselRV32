import chisel3._
import chisel3.util._
import rsd_rv32.common._

class DecodedInst extends Bundle {
  val DC_instType = UInt(3.W)      //1=R, 2=I,3=S,4=B,5=U,6=J
  val DC_instName = UInt(8.W)      //每个指令在其类别内的顺序，从1开始，按群里link的顺序为准
  val DC_rd = UInt(5.W)            //目标寄存器
  val DC_imm = UInt(32.W)          //立即数（没有则为0）
  val DC_rs1 = UInt(5.W)           //指令中出现的第一个寄存器，下同
  val DC_rs2 = UInt(5.W)
  val DC_rs3 = UInt(5.W)
  
}
