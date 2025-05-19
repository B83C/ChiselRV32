package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

//功能单元的抽象类，定义了底层模块端口
abstract class FunctionalUnit(
)(implicit p: Parameters) extends Module {
  def supportedInstrTypes: Set[InstrType.Type]
  val io = IO(new Bundle {
    val uop = Flipped(Decoupled(new EXUISSUE_EXU_uop()))
    val reset = Input(Bool())
  })
}
