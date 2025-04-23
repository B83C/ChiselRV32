package rsd_rv32.common

import chisel3._
import chisel3.util._

case class Parameters(
  XLEN: Int = 32,
  //PC_WIDTH: Int = 32,
  CORE_WIDTH: Int = 2,  //Dispatch, Issue, Commit Width
  INSTR_WIDTH: Int = 32,
  // FETCH_WIDTH: Int = 2,
  // SCALE: Int = 2,
  // RENAME_WIDTH: Int = 2,
  // DISPATCH_WIDTH: Int = 2,
  // ISSUE_WIDTH: Int = 2,
  GHR_WIDTH: Int = 8,

  FU_NUM: Int = 7,
  ALU_NUM: Int = 4,
  BU_NUM: Int = 1,
  STU_NUM: Int = 1,
  LDU_NUM: Int = 1,

  PRF_DEPTH: Int = 128,

  ROB_DEPTH: Int = 256,

  STQ_DEPTH: Int = 32,
  
  EXUISSUE_DEPTH: Int = 16,
  LDISSUE_DEPTH: Int = 16,
  STISSUE_DEPTH: Int = 16
) {
  
}


