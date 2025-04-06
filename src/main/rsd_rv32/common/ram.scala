package rsd_rv32.common

import chisel3._
import chisel3.util._

class Port(val AddrWidth: Int,
   val DataWidth: Int,
   val read: Bool)(implicit p: Parameters) extends Bundle {
  val addr = Input(UInt(AddrWidth.W))
  val data = if read Output(UInt(DataWidth.W)) else Input(UInt(DataWidth.W))
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
