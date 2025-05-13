package rsd_rv32.common

import chisel3._
import chisel3.util._

package object Utils {
  def is[T, R](result: R, allowed: T*)(implicit value: T): Option[R] =
    if(allowed.contains(value)) Some(result) else None
}
