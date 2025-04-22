package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class Dispatcher_IO(implicit p:Parameters) extends Bundle {
  // with Rename
  val rename_uop = Decoupled(Vec(p.RENAME_WIDTH,new RENAME_DISPATCH_uop())).flip
  // with ROB
  val rob_uop = Output(Valid(Vec(p.DISPATCH_WIDTH,new DISPATCH_ROB_uop())))
  val rob_empty = Input(Bool())
  val rob_head = Input(UInt(log2Ceil(p.ROB_DEPTH).W))
  val rob_tail = Input(UInt(log2Ceil(p.ROB_DEPTH).W))
  val rob_commitsignal = ßValid(Vec(p.DISPATCH_WIDTH, UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))).flip
  // with exu_issue
  val exuissue_uop = Valid(Vec(p.DISPATCH_WIDTH,new DISPATCH_EXUISSUE_uop()))
  val exuissued_id = Valid(Vec(p.EXU_ISSUE_WIDTH,UInt(log2Ceil(p.EXUISSUE_DEPTH).W))).flip
  // with st_issue
  val stissue_uop = Valid(Vec(p.DISPATCH_WIDTH,new DISPATCH_STISSUE_uop()))
  val stissued_id = Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)).flip
  // with ld_issue
  val ldissue_uop = Valid(Vec(p.DISPATCH_WIDTH,new DISPATCH_LDISSUE_uop()))
  val ldissued_id = Valid(UInt(log2Ceil(p.LDISSUE_DEPTH).W)).flip
  // with sq
  val stq_empty = Input(Bool())
  val stq_head = Input(UInt(log2Ceil(p.STQ_DEPTH).W))
  val stq_tail = Input(UInt(log2Ceil(p.STQ_DEPTH).W))
}

class Dispatcher(implicit p: Parameters) extends Module{
  val io = IO(new Dispatcher_IO)
}
