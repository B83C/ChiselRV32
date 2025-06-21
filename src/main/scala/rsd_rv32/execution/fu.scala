package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._
import rsd_rv32.scheduler.ROBControlSignal

//功能单元的抽象类，定义了底层模块端口
abstract class FunctionalUnit(
)(implicit p: Parameters) extends CustomModule {
  val properties: FUProps
  // def supportedInstrTypes: Set[InstrType.Type]
  // def needBufferedInput: Bool = true.B
  // def needBuferredOutput: Bool = true.B
  
  val io = IO(new Bundle {
    val uop = Flipped(Decoupled(new EXUISSUE_EXU_uop()))
    val out = Valid(new WB_uop())
    
    val rob_controlsignal = Flipped(new ROBControlSignal())
  })
  
  val input = io.uop
  val output = io.out
  
  output.bits.debug := input.bits.debug
}

case class FUProps(
  supportedInstr: Set[InstrType.Type],
  bufferedInput: Boolean = true,
  bufferedOutput: Boolean = true,
)
