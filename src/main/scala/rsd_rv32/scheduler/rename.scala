package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

// 重命名单元将逻辑寄存器地址映射成实际寄存器。逻辑寄存器指的是ISA定义的x0-x31，而实际寄存器数量多于32个，一般可达128个。主要解决WAW，WAR等问题。
class RenameUnit_IO(implicit p: Parameters) extends Bundle {
  //with ID
  val id_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new ID_RENAME_uop())))
  val ready = Output(Bool()) // Rename单元是否准备好接收指令
  //with ROB
  val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))))
  //with Dispatch
  val dispatch_uop = Vec(p.CORE_WIDTH, Valid(new RENAME_DISPATCH_uop()))
  val dispatch_ready = Input(Bool()) // Dispatch单元是否准备好接收指令
}

class Rename(implicit p: Parameters) extends Module {
  val io = IO(new RenameUnit_IO())
}


