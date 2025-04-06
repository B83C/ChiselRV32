package rsd_rv32.common

import chisel3._
import chisel3.util._

case class Parameters (
  XLEN: Int = 32,
  SCALE: Int = 2,
  RENAME_WIDTH: Int = 2,
  DISPATCH_WIDTH: Int = 2,
  ISSUE_WIDTH: Int = 2
)


