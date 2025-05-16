package rsd_rv32.common

import chisel3._
import chisel3.util._
import rsd_rv32.common._

package object Utils {
  def is[T, R](result: R, allowed: T*)(implicit value: T): Option[R] =
    if(allowed.contains(value)) Some(result) else None
  // def is[T, R, E](result: R, else_result: E, allowed: T*)(implicit value: T): Option[R] =
  //   if(allowed.contains(value)) result else E

  def immExtract(instr: UInt, instr_type: IType.Type) : UInt = {
    import IType._
    instr match {
      case I => instr(31,20)
      case S => Cat(instr(31,25),instr(11,7))
      case B => Cat(instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
      case U => Cat(instr(31, 12), 0.U(12.W))
      case J => Cat(instr(31), instr(19,12), instr(20), instr(30,21), 0.U(1.W))
    }
  }

  case class Request[T <: Data](data: T) extends Bundle {
    val bits = Output(data)
    val ready = Input(Bool())
  }

}
