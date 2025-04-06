package rsd_rv32.common

import chisel3._
import chisel3.util._

abstract class FreeList(
  val read_ports: Int,
  val depth: Int,
)(implicit p: Parameters) extends Module {
  val io = IO(
    val requests = Input(Vec(depth, Bool()))
    val data  = Output(Vec(depth, Valid(UInt(data_width.W))))

    val dealloc  = Input(Vec(depth, Valid(UInt(data_width.W))))
  )
}

// 获取PRF可用的寄存器
class PRFFreeList(
  val read_ports: Int,
  val depth: Int,
  val data_width: Int,
)(implicit p: Parameters) extends FreeList(read_ports, depth) {


}
