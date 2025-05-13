package rsd_rv32.common

import chisel3._
import chisel3.util._

case class Parameters(
  XLEN: Int = 32,
  //PC_WIDTH: Int = 32,
  CORE_WIDTH: Int = 2,  //Dispatch, Issue, Commit Width
  INSTR_WIDTH: Int = 32,
  GHR_WIDTH: Int = 8,

  FU_NUM: Int = 8,
  ALU_NUM: Int = 2,
  BU_NUM: Int = 1,
  MUL_NUM: Int = 1,
  DIV_NUM: Int = 1,
  STU_NUM: Int = 1,
  LDU_NUM: Int = 1,
  CSRU_NUM: Int = 1,

  PRF_DEPTH: Int = 128,

  ROB_DEPTH: Int = 256,

  STQ_DEPTH: Int = 32,

  EXUISSUE_DEPTH: Int = 16,
  LDISSUE_DEPTH: Int = 16,
  STISSUE_DEPTH: Int = 16,
  EXU_Fj_CNT_MAX: Int = 10, //一个执行单元所包含的FU最多的数量
) {
  
}

object CpuModuleEnum extends Enumeration {
  type CpuModuleEnum = Value
  val F, BP, RN, DP, EI, SI, LI, ROB, PRF, WB = Value
}

class CustomBundle(implicit val p: Parameters) extends Bundle

class CustomModule extends Module {
  import CpuModuleEnum._
  implicit lazy val ModuleName: CpuModuleEnum = this.getClass.getSimpleName match {
    case "ROB" => ROB
    case _ => None
  }
}

