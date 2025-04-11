import chisel13._
import chisel13.util._

class FetchInput extends Bundle ={
  val FC_pc = UInt(PC_WIDTH.W)
  val Instr = Vec(FETCH_WIDTH, UInt(INSTR_WIDTH.W))
  val Valid = Vec(FETCH_WIDTH, Bool())
}

class FetchOutput extends Bundle ={
  val FC_inst = UInt(INSTR_WIDTH.W)
}
