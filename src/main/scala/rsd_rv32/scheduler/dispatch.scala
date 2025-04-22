package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class Dispatcher_IO(implicit p:Parameters) extends Bundle {
  // with Rename
  val rename_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new RENAME_DISPATCH_uop())))
  val ready = Output(Bool()) // Dispatch单元是否准备好接收指令
  // with ROB
  val rob_uop = Vec(p.CORE_WIDTH, Valid(new DISPATCH_ROB_uop()))
  val rob_empty = Input(Bool())
  val rob_head = Input(UInt(log2Ceil(p.ROB_DEPTH).W))
  val rob_tail = Input(UInt(log2Ceil(p.ROB_DEPTH).W))
  val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))))
  // with exu_issue
  val exuissue_uop = Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop()))
  val exuissued_id = Vec(p.CORE_WIDTH, Flipped(Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W))))
  // with st_issue
  val stissue_uop = Vec(p.CORE_WIDTH, Valid(new DISPATCH_STISSUE_uop()))
  val stissued_id = Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)).flip
  // with ld_issue
  val ldissue_uop = Vec(p.CORE_WIDTH, Valid(new DISPATCH_LDISSUE_uop()))
  val ldissued_id = Valid(UInt(log2Ceil(p.LDISSUE_DEPTH).W)).flip
  // with sq
  val stq_empty = Input(Bool())
  val stq_head = Input(UInt(log2Ceil(p.STQ_DEPTH).W))
  val stq_tail = Input(UInt(log2Ceil(p.STQ_DEPTH).W))
  val dispatched_st = Vec(p.CORE_WIDTH, Bool()) //本cycle派遣store指令的情况
}

class Dispatcher(implicit p: Parameters) extends Module{
  val io = IO(new Dispatcher_IO)
}
