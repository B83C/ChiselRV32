import chisel3._
import chisel3.util._
import rsd_rv32.common._

class Decode_IO(implicit p: Parameters) extends Bundle {
  // with IF
  val if_uop = Decoupled(new IF_ID_uop()).flip
  // with Rename
  val rename_uop = Valid(Vec(p.RENAME_WIDTH, new ID_RENAME_uop()))
  // with ROB
  val rob_commitsignal = Valid(Vec(p.DISPATCH_WIDTH, UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))).flip
}

class Decode(implicit p: Parameters) extends Module {
  val io = IO(new Decode_IO())
}
