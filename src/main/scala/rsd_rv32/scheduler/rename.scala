package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

// 重命名单元将逻辑寄存器地址映射成实际寄存器。逻辑寄存器指的是ISA定义的x0-x31，而实际寄存器数量多于32个，一般可达128个。主要解决WAW，WAR等问题。
class RenameUnit_IO(implicit p: Parameters) extends Bundle {
  //with ID
  val id_uop = Valid(Vec(p.RENAME_WIDTH, new ID_RENAME_uop())).flip
  val prf_freelist_head = Output(UInt(log2Ceil(p.PRF_DEPTH-32).W))
  val prf_freelist_tail = Output(UInt(log2Ceil(p.PRF_DEPTH-32).W))
  val prf_freelist_empty = Output(Bool())
  //with ROB
  val rob_commitsignal = Valid(Vec(p.DISPATCH_WIDTH, UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))).flip
  //with Dispatch
  val dispatch = Decoupled(Vec(p.DISPATCH_WIDTH, new RENAME_DISPATCH_uop()))
}

class Rename(implicit p: Parameters) extends Module {
  val io = IO(new RenameUnit_IO())
}


