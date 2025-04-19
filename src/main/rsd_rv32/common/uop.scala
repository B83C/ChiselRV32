package rsd_rv32.common

import chisel3._
import chisel3.util._

object InstrType extends ChiselEnum {
    val ALU, Branch, LSU, csr, MUL = Value
}

object BranchPred extends ChiselEnum {
    val T, NT = Value
}

object BTBHit extends ChiselEnum{
    val H, NH = Value
}

object OprSel extends ChiselEnum {
    val IMM, REG, PC, Z = Value
}

trait Signals {
    val width = (new this).getWidth
}

class ALUSignals extends Bundle with Signals{
    val req_immediate = Bool()
    val opr1_sel = OprSel()
    val opr2_sel = OprSel()
}

class BranchSignals extends Bundle with Signals{
    val jal = Bool()
    val jalr = Bool()
}

class LSUSignals extends Bundle with Signals{
    val load = Bool()
    val store = Bool()
}

class FUSignals extends Bundle {
    val bits = UInt(ALUSignals.width max BranchSignals.width max LSUSignals.width)
    def as_ALU = this.asTypeOf(new ALUSignals)
    def as_Branch = this.asTypeOf(new BranchSignals)
    def as_LSU = this.asTypeOf(new LSUSignals)
}

class uop()(implicit p: Parameters) extends Bundle {
    val instr = UInt((32 - 7).W) //func3, func7, rd, rs1 , rs2, imm without opcode
    val instr_type = InstrType()
    val instr_PC = UInt(p.XLEN.W)
    val target_PC = UInt(p.XLEN.W)
    val GHR = UInt(p.GHR_WIDTH.W)
    // Signals created in the middle of the pipeline
    val pd = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val branch_pred = BranchPred()
    val btb_hit = BTBHit()
    
    val fu_signals = new FUSignals() //opcode is compiled into fu specific control signals
}



