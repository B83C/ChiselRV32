package rsd_rv32.common

import chisel3._
import chisel3.util._

class Port(
  val addr_width: Int,
   val data_width: Int,
   val read: Boolean,
 )(implicit p: Parameters) extends CustomBundle {
  val addr = Input(UInt(addr_width.W))
  val data = if (read) Output(UInt(data_width.W)) else Input(UInt(data_width.W))
}

class MultiportRam(
  val ReadersCount: Int,
  val WriterCount: Int,
  val Depth: Int,
  val DataWidth: Int
)(implicit p: Parameters) extends Module {
  val addr_width = log2Ceil(Depth)
  val io = IO(new Bundle {
    val readers = Input(Vec(ReadersCount, new Port(addr_width, DataWidth, true)))
    val writers = Input(Vec(WriterCount, new Port(addr_width, DataWidth, false)))
  })
}
