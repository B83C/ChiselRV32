package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class Decode_IO(implicit p: Parameters) extends Bundle {
  // with IF
  val if_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new IF_ID_uop())))
  val ready = Output(Bool()) // ID是否准备好接收指令
  // with Rename
  val rename_uop = Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop()))
  val rename_ready = Input(Bool()) // Rename是否准备好接收指令
  // with ROB
  val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))))
}

class Decode(implicit p: Parameters) extends Module {
  val io = IO(new Decode_IO())
}
