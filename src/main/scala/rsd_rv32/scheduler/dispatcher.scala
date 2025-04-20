package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.scheduler.rob._

class Dispatcher_IO(implicit p:Parameters) extends Bundle {
  // with Rename
  val Rename_uop = Decoupled(Vec(p.RENAME_WIDTH,new uop())).flip
  // with ROB
  val ROB_uop = Valid(Vec(p.DISPATCH_WIDTH,new uop()))
  val ROB_empty = Input(Bool())
  val ROB_head = Input(UInt(log2Ceil(p.ROB_DEPTH).W))
  val ROB_tail = Input(UInt(log2Ceil(p.ROB_DEPTH).W))
  // with exu_issue
  val exu_issue_uop = Valid(Vec(p.DISPATCH_WIDTH,new uop()))
  // with st_issue
  val st_issue_uop = Valid(Vec(p.DISPATCH_WIDTH,new uop()))
  // with ld_issue
  val ld_issue_uop = Valid(Vec(p.DISPATCH_WIDTH,new uop()))
  // with sq
  val stq_empty = Input(Bool())
  val stq_head = Input(UInt(log2Ceil(p.STQ_DEPTH).W))
  val stq_tail = Input(UInt(log2Ceil(p.STQ_DEPTH).W))
}