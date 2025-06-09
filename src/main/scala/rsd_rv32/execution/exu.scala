package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._
import rsd_rv32.scheduler._


class EXUIO(fu_num: Int)(implicit p: Parameters) extends Bundle{
  //来自exu_issue queue的输入
  val execute_uop = Flipped(Vec(fu_num, Decoupled(new EXUISSUE_EXU_uop)))
  
  val serialised_uop = Flipped(Valid(new EXUISSUE_EXU_uop))

  val rob_controlsignal = Flipped(Valid(new ROBControlSignal)) //来自于ROB的控制信号
  
  //写回信号
  val wb_uop = Vec(fu_num, Valid(new WB_uop()))
  
  val bu_signals = Valid(new BU_uop)
  val serialised_wb_uop = Valid(new WB_uop())
}

//把exu的各个fu封装起来的顶层模块
class EXU(implicit p: Parameters) extends Module {
  val alu = Seq.fill(p.ALU_NUM)(Module(new ALUFU))
  val bu = Seq.fill(p.BU_NUM)(Module(new BranchFU))
  val mul = Seq.fill(p.MUL_NUM)(Module(new MULFU))
  val div = Seq.fill(p.DIV_NUM)(Module(new DIVFU))
  val fus = (bu ++ alu ++ mul ++ div)
  val io = IO(new EXUIO(fus.length))
  
  def fus_properties: Seq[FUProps] = fus.map(_.properties)

  val mispred = io.rob_controlsignal.valid && io.rob_controlsignal.bits.isMispredicted
  
  require(bu.length <= 1, "Currently supports only 1 BU")
  
  withReset(reset.asBool || mispred) {
    fus.zip(io.execute_uop).foreach { case (fu, in_uop) => 
      fu.input.valid := in_uop.valid
      fu.input.bits := in_uop.bits
      in_uop.ready := fu.input.ready
    }

    io.bu_signals := RegEnableValid(bu(0).bu_out)
  

    // Note that checks if instr_type is as subset of any FUs' supported types
    io.wb_uop := VecInit(fus.map(fu => RegEnableValid(fu.output)))

    // In-order FUs should deserve better attention
    val csru = Module(new CSRFU_Default)
    //Serialised uop
    csru.input.bits := io.serialised_uop.bits
    csru.input.valid := io.serialised_uop.valid
    // CSRU should always be ready? 
    io.serialised_wb_uop := csru.output
  } 
}

object EXU {

  type InstrTypeSets = Seq[Set[InstrType.Type]]
 

  def get_indicies_of_fus_that_support(instr_type_mapping: Seq[FUProps])(instr_type: Set[InstrType.Type]): Seq[Int] = {
    instr_type_mapping.zipWithIndex.collect{
      case (x, y) if instr_type.toSet.subsetOf(x.supportedInstr) => y
    }
  }
  
  def get_mapping_of_fus_that_support[T <: Data](instr_type_mapping: Seq[FUProps])(instr_type: Set[InstrType.Type])(vec: Vec[T]): Seq[T] = {
   val indices = get_indicies_of_fus_that_support(instr_type_mapping)(instr_type)

    require(indices.nonEmpty, s"No functional units all the features: ${instr_type.mkString(", ")}")

    indices.map(vec(_))
  }
}
