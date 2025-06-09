package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

//功能单元的抽象类，定义了底层模块端口
abstract class FunctionalUnit(
)(implicit p: Parameters) extends Module {
  val properties: FUProps
  // def supportedInstrTypes: Set[InstrType.Type]
  // def needBufferedInput: Bool = true.B
  // def needBuferredOutput: Bool = true.B
  
  val io = IO(new Bundle {
    val uop = Flipped(Decoupled(new EXUISSUE_EXU_uop()))
    val out = Valid(new WB_uop())
  })
  
  val input = io.uop
  val output = io.out
  
  // Debugging
  output.bits.debug := input.bits.debug
  // val input = Wire(Flipped(Decoupled(new EXUISSUE_EXU_uop())))
  // val output = Wire(Valid(new WB_uop()))
  // io.uop.ready := input.ready
  // if(properties.bufferedInput) {
  //   input.bits := RegEnable(io.uop.bits, io.uop.valid)
  //   input.valid := RegNext(io.uop.valid)
  // } else {
  //   input.bits := io.uop.bits
  //   input.valid := io.uop.valid
  // }
  // if(properties.bufferedOutput) {
  //   io.out.bits := RegEnable(output.bits, output.valid)
  //   io.out.valid := RegNext(output.valid)
  // } else {
  //   io.out.bits := output.bits
  //   io.out.valid := output.valid
  // }
}

case class FUProps(
  supportedInstr: Set[InstrType.Type],
  bufferedInput: Boolean = true,
  bufferedOutput: Boolean = true,
)
