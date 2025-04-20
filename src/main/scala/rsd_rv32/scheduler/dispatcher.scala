package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.scheduler.rob._

class Dispatcher_IO(implicit p:Parameters) extends Bundle {
  // with Rename
  val rename_uop = Decoupled(Vec(p.RENAME_WIDTH,new RENAME_DISPATCH_uop())).flip
  // with ROB
  val rob_uop = Valid(Vec(p.DISPATCH_WIDTH,new DISPATCH_ROB_uop()))
  val rob_empty = Input(Bool())
  val rob_head = Input(UInt(log2Ceil(p.ROB_DEPTH).W))
  val rob_tail = Input(UInt(log2Ceil(p.ROB_DEPTH).W))
  // with exu_issue
  val exuissue_uop = Valid(Vec(p.DISPATCH_WIDTH,new DISPATCH_EXUISSUE_uop()))
  // with st_issue
  val stissue_uop = Valid(Vec(p.DISPATCH_WIDTH,new DISPATCH_STISSUE_uop()))
  // with ld_issue
  val ldissue_uop = Valid(Vec(p.DISPATCH_WIDTH,new DISPATCH_LDISSUE_uop()))
  // with sq
  val stq_empty = Input(Bool())
  val stq_head = Input(UInt(log2Ceil(p.STQ_DEPTH).W))
  val stq_tail = Input(UInt(log2Ceil(p.STQ_DEPTH).W))
}