package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

import rsd_rv32.frontend._

class EXUIO(fu_num: Int)(implicit p: Parameters) extends Bundle{
  //来自exu_issue queue的输入
  val execute_uop = Flipped(Vec(fu_num, Valid(new EXUISSUE_EXU_uop)))
  
  val serialised_uop = Flipped(Valid(new EXUISSUE_EXU_uop))

  // val readys = (Vec(fu_num, Bool()))

  // val wb = (Vec(p.FU_NUM, Valid(new WB_uop())))
  
  // val branch_info = (new FUBranchInfo())
  //val rob_signal = Flipped(new ROBSignal())
  val rob_commitsignal = Flipped(Vec(p.CORE_WIDTH, Valid(new ROBContent())))
  //flush = rob_commitsignal(0).valid && rob_commitsignal(0).bits.mispred
  // Should be removed in place of more general readys
  //反馈给exu_issue queue的信号
  val mul_ready = (Bool())
  val div_ready = (Bool())

  // val wb_uop = Vec(fu_num, Valid(new WB_uop()))
  //写回信号
  val alu_wb_uop = Vec(p.ALU_NUM, Valid(new ALU_WB_uop()))
  val bu_wb_uop = Vec(p.BU_NUM, Valid(new BU_WB_uop()))
  val mul_wb_uop = Vec(p.MUL_NUM, Valid(new ALU_WB_uop()))
  val divrem_wb_uop = Vec(p.DIV_NUM, Valid(new ALU_WB_uop()))
  
  val serialised_wb_uop = Valid(new ALU_WB_uop())
}

//把exu的各个fu封装起来的顶层模块
class EXU(implicit p: Parameters) extends Module {
  val alu = Seq.fill(p.ALU_NUM)(Module(new ALUFU))
  val bu = Seq.fill(p.BU_NUM)(Module(new BranchFU))
  val mul = Seq.fill(p.MUL_NUM)(Module(new MULFU))
  val div = Seq.fill(p.DIV_NUM)(Module(new DIVFU))

  val fus = (alu ++ bu ++ mul ++ div)
  val io = IO(new EXUIO(fus.length))
  fus.foreach { fu => 
    (fu.io: Data).waiveAll :<>= (io: Data).waiveAll
  }
  
  // io.readys := VecInit((alu ++ bu ++ mul ++ div ++ csru).map(!_.io.uop.ready))
  //TODO 改成readys
  io.mul_ready  := VecInit((mul).map(_.io.uop.ready)).asUInt.orR
  io.div_ready  := VecInit((div).map(_.io.uop.ready)).asUInt.orR
  def get_readys_instr_type: Set[InstrType.Type] = fus.map(_.supportedInstrTypes).reduce(_ ++ _)

  // io.wb_uop := VecInit(fus.map(_.io.out))
  io.alu_wb_uop := VecInit((alu).map(_.out))
  io.bu_wb_uop := VecInit(bu.map(_.out))
  io.mul_wb_uop := VecInit(mul.map(_.out))
  io.divrem_wb_uop := VecInit(div.map(_.out))


  val csru = Module(new CSRFU_Default)
  //Serialised uop
  csru.uop := io.serialised_uop
  //TODO
  // csru.reset := 
  io.serialised_wb_uop := csru.out
}
