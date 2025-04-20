package rsd_rv32.common

import chisel3._
import chisel3.util._

object InstrType extends ChiselEnum {
    val ALU, Branch, LD, ST, csr, MUL = Value
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
    val width = this.getWidth
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

class FUSignals extends Bundle {
    val bits = UInt(ALUSignals.width max BranchSignals.width max LSUSignals.width)
    def as_ALU = this.asTypeOf(new ALUSignals)
    def as_Branch = this.asTypeOf(new BranchSignals)
}

/*class uop()(implicit p: Parameters) extends Bundle {
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
} */

class IF_ID_uop(implicit p: Parameters) extends Bundle {
    val instr = UInt(p.XLEN.W) 
    val instr_PC = UInt(p.XLEN.W)
    val target_PC = UInt(p.XLEN.W)
    val GHR = UInt(p.GHR_WIDTH.W)
    val branch_pred = BranchPred()
    val btb_hit = BTBHit()
}

class ID_RENAME_uop(implicit p: Parameters) extends Bundle {
    val instr = UInt((p.XLEN-7).W) //func3, func7, rd, rs1 , rs2, imm without opcode
    val instr_type = InstrType()
    val fu_signals = new FUSignals() //opcode is compiled into fu specific control signals
    val instr_PC = UInt(p.XLEN.W)
    val target_PC = UInt(p.XLEN.W)
    val GHR = UInt(p.GHR_WIDTH.W)
    val branch_pred = BranchPred()
    val btb_hit = BTBHit()
}

class RENAME_DISPATCH_uop(implicit p: Parameters) extends ID_uop {
    val pd = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W)
}

class DISPATCH_ROB_uop(implicit p: Parameters) extends Bundle {
    val instr_PC = UInt(p.XLEN.W)
    val instr = UInt((p.XLEN-7).W) //including rd
    val instr_type = InstrType()
    val fu_signals = new FUSignals() //needed to distinguish between conditional branch and unconditioal 
    val GHR = UInt(p.GHR_WIDTH.W)
    val pd = UInt(log2Ceil(p.PRF_DEPTH).W)
    val branch_pred = BranchPred()
    val btb_hit = BTBHit()
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
}

class DISPATCH_EXUISSUE_uop(implicit p: Parameters) extends Bundle {
    val instr = UInt((p.XLEN-7).W) //func3, func7, rd, rs1 , rs2, imm without opcode
    val instr_type = InstrType()
    val fu_signals = new FUSignals() //opcode is compiled into fu specific control signals
    val iq_id = UInt(log2Ceil(p.EXUISSUE_DEPTH).W)
    val branch_pred = BranchPred()
    val pd = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
}

class DISPATCH_LDISSUE_uop(implicit p: Parameters) extends Bundle {
    val instr = UInt((p.XLEN-7).W)
    val iq_id = UInt(log2Ceil(p.LDISSUE_DEPTH).W)
    val pd = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val stq_tail = UInt(log2Ceil(p.STQ_DEPTH).W)
}

class DISPATCH_STISSUE_uop(implicit p: Parameters) extends Bundle {
    val instr = UInt((p.XLEN-7).W)
    val iq_id = UInt(log2Ceil(p.STISSUE_DEPTH).W)
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
}
