package rsd_rv32.common

import chisel3._
import chisel3.util._

case class Parameters(
  ENTRY_PC: Int = 0x0, 
  XLEN: Int = 32,

  REG_CNT: Int = 32, // Architectural reg counts

  //PC_WIDTH: Int = 32,
  CORE_WIDTH: Int = 2,  //Dispatch, Issue, Commit Width
  INSTR_WIDTH: Int = 32,
  GHR_WIDTH: Int = 8,
  BRANCH_MASK_WIDTH: Int = 16, // Can handle up to 8 in-flight branches 

  BIMODE_TABLE_SIZE: Int =  1024,          // T/NT 表大小
  CHOICE_TABLE_SIZE: Int =  1024,          // 选择器表大小
  COUNTER_BITS: Int =  2,                 // 饱和计数器位宽
  BTB_SIZE: Int =  512,                   // BTB 表大小
  INST_BYTES: Int =  4,                   // 指令字节宽度

  ALU_NUM: Int = 2,
  BU_NUM: Int = 1,
  MUL_NUM: Int = 1,
  DIV_NUM: Int = 1,
  STU_NUM: Int = 1,
  LDU_NUM: Int = 1,
  CSRU_NUM: Int = 1,
  FU_NUM: Int = 8,

  PRF_DEPTH: Int = 128,

  ROB_DEPTH: Int = 256,

  STQ_DEPTH: Int = 32,

  //TODO: Merge all of them
  ISSUE_FREELIST_DEPTH: Int = 16,
  ISSUE_DEPTH: Int = 16,
  EXUISSUE_DEPTH: Int = 16,
  LDISSUE_DEPTH: Int = 16,
  STISSUE_DEPTH: Int = 16,
  EXU_Fj_CNT_MAX: Int = 10, //一个执行单元所包含的FU最多的数量

  CSR_MTIME_ADDR: Int = 0xC01,
  CSR_MCYCLE_ADDR: Int = 0xC00,
)

object IType extends ChiselEnum {
  val
    R,
    I,
    S,
    B,
    U,
    J,
    Invalid
     = Value
}

object FUType extends ChiselEnum {
   val
     ALU,
     BU,
     MUL,
     DIV,
     CSR
     = Value
}

object bl {
  def apply(value: Int) = {
    log2Ceil(value).W
  }
}


//为了简化interface的设计，对每个知名的模块定义一个名称，这样使用起来就统一了
object CpuModuleEnum extends Enumeration {
  type CpuModuleEnum = Value
  val
     F, //Fetch Unit
    BP, //Branch Predictor Unit
    RN, //Rename Unit
    DP, //Dispatch Unit
    ROB, //Reorder buffer Unit
    EI, //Execute Issue Unit
    SI, //Store Issue Unit
    LI, //Load Issue Unit
    ALU, //ALU FU
    WB //WriteBack Unit
    = Value
}

import CpuModuleEnum._
class CustomBundle(implicit p: Parameters, pt: Option[CpuModuleEnum] = None) extends Bundle

class CustomModule extends Module {
  import CpuModuleEnum._
  implicit lazy val ModuleName: Option[CpuModuleEnum] = this.getClass.getSimpleName match {
    case "ROB" => Some(ROB)
    case _ => None
  }
}

