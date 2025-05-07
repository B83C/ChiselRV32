package rsd_rv32.common

import chisel3._
import chisel3.util._

// 参数化的Freelist
abstract class FreeList(
  val read_ports: Int, /* Number of read ports */
  val depth: Int,      /* Depth of the freelist buffer */
  val data_width: Int,
)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val requests = Input(Vec(depth, Bool()))
    val data  = Output(Vec(depth, Valid(UInt(data_width.W))))
    val empty = Output(Bool())//无空闲 置高
    val dealloc  = Input(Vec(depth, Valid(UInt(data_width.W))))
  })
}

// 获取PRF中可用的寄存器
class PRFFreeList(
  val read_ports: Int,  /* with desc */ 
  val depth: Int,  //Another desc
  val data_width: Int,
)(implicit p: Parameters) extends FreeList(read_ports, depth, data_width) {
}
