package rsd_rv32.common

import chisel3._
import chisel3.util._
import rsd_rv32.common._

package object Utils {
  def is[T, R](result: R, allowed: T*)(implicit value: T): Option[R] =
    if(allowed.contains(value)) Some(result) else None
  // def is[T, R, E](result: R, else_result: E, allowed: T*)(implicit value: T): Option[R] =
  //   if(allowed.contains(value)) result else E

  case class Request[T <: Data](data: T) extends Bundle {
    val bits = Output(data)
    val ready = Input(Bool())
  }
}
