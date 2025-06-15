package rsd_rv32.common

import chisel3._
import chisel3.util._

object InstrType extends ChiselEnum {
    val ALU, Branch, Jump, LD, ST, CSR, MUL, DIV_REM = Value
}

object OprSel extends ChiselEnum {
    val IMM, REG, PC, Z = Value
}

class BranchInfo(implicit p: Parameters) extends Bundle {
    val branch_id = UInt(log2Ceil(p.BRANCH_MASK_WIDTH).W)
}

abstract class uop(implicit p: Parameters) extends CustomBundle {
    def prf_read_counts: Int = 0

    val branch_mask = UInt(p.BRANCH_MASK_WIDTH.W)
    val branch_id = UInt(log2Ceil(p.BRANCH_MASK_WIDTH).W)

    // Debugging
    val debug = new InstrDebug
} 

class IF_ID_uop(implicit p: Parameters) extends uop {
    val instr = UInt(p.XLEN.W) 
    val instr_addr = UInt(p.XLEN.W) //needed by rob, BU, ALU
    val predicted_next_pc = UInt(p.XLEN.W) //needed by BU
    val ghr = UInt(p.GHR_WIDTH.W) //needed by rob
    val branch_taken = Bool() //needed by BU

    val branch_freed = UInt(p.BRANCH_MASK_WIDTH.W)
}

class ID_RENAME_uop(implicit p: Parameters) extends uop {
    //opcode is compiled into fu specific control signals

    val instr_type = InstrType() 
    val instr_ = UInt((p.XLEN-7).W) //func3, func7, rd, rs1 , rs2, imm without opcode

    val opr1_sel = OprSel()
    val opr2_sel = OprSel()

    val instr_addr = UInt(p.XLEN.W)
    val predicted_next_pc = UInt(p.XLEN.W)

    val ghr = UInt(p.GHR_WIDTH.W)
    val branch_taken = Bool()

    val rd = Valid(UInt(bl(p.REG_CNT)))
    val rs1 = UInt(bl(p.REG_CNT))
    val rs2 = UInt(bl(p.REG_CNT))

    val branch_freed = UInt(p.BRANCH_MASK_WIDTH.W)
}

//继承ID_RENAME的uop
class RENAME_DISPATCH_uop(implicit p: Parameters) extends ID_RENAME_uop {
    override def prf_read_counts: Int = 2
    val pdst = Valid(UInt(bl(p.PRF_DEPTH)))
    val ps1 = UInt(bl(p.PRF_DEPTH))
    val ps2 = UInt(bl(p.PRF_DEPTH))
}

class DISPATCH_ROB_uop(implicit p: Parameters) extends uop {
    val instr_addr = UInt(p.XLEN.W)

    val instr_type = InstrType()
    val opr1_sel = OprSel()
    val opr2_sel = OprSel()

    val pdst = Valid(UInt(bl(p.PRF_DEPTH)))
    val rd = Valid(UInt(5.W))

    val branch_freed = UInt(p.BRANCH_MASK_WIDTH.W)
    // val ghr = UInt(p.GHR_WIDTH.W)
    //val branch_pred = BranchPred()
    // val btb_hit = BTBHit()
    
    //val rob_index = UInt(bl(p.ROB_DEPTH))
    //val rob_inner_index = UInt(bl(p.CORE_WIDTH))
}

class DISPATCH_EXUISSUE_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 2
    val instr_ = UInt((p.XLEN-7).W) //EXU need it to get imm,func,etc
    val instr_addr = UInt(p.XLEN.W)

    val instr_type = InstrType()
    val opr1_sel = OprSel()
    val opr2_sel = OprSel()

    val branch_taken = Bool()
    val predicted_next_pc = UInt(p.XLEN.W)

    val pdst = Valid(UInt(bl(p.PRF_DEPTH)))
    val ps1 = UInt(bl(p.PRF_DEPTH))
    val ps2 = UInt(bl(p.PRF_DEPTH))

    val rob_index = UInt(bl(p.ROB_DEPTH) + 1.W)
    val rob_inner_index = UInt(bl(p.CORE_WIDTH))

    val iq_index = UInt(bl(p.EXUISSUE_DEPTH))

    // TODO
    val ghr = UInt(p.GHR_WIDTH.W)
}

class DISPATCH_LDISSUE_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 1
    val instr_ = UInt((p.XLEN-7).W) //Load Pipeline need it to get imm,func,etc
    
    val pdst = Valid(UInt(bl(p.PRF_DEPTH)))
    val ps1 = UInt(bl(p.PRF_DEPTH))
    val stq_tail = UInt(bl(p.STQ_DEPTH)) //needed to get the right forwarding data from STQ

    val rob_index = UInt(bl(p.ROB_DEPTH) + 1.W)
    val rob_inner_index = UInt(bl(p.CORE_WIDTH))

    val iq_index = UInt(bl(p.LDISSUE_DEPTH))
}

class DISPATCH_STISSUE_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 2
    val instr_ = UInt((p.XLEN-7).W) //Store Pipeline need it to get imm,func
    
    val ps1 = UInt(bl(p.PRF_DEPTH))
    val ps2 = UInt(bl(p.PRF_DEPTH))

    val stq_index = UInt(bl(p.STQ_DEPTH)) //needed to writeback to STQ
    val rob_index = UInt(bl(p.ROB_DEPTH) + 1.W)
    val rob_inner_index = UInt(bl(p.CORE_WIDTH))

    val iq_index = UInt(bl(p.STISSUE_DEPTH))
}

class EXUISSUE_EXU_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 2
    val instr_ = UInt((p.XLEN-7).W)
    val instr_addr = UInt((p.XLEN).W)

    val instr_type = InstrType()
    val opr1_sel = OprSel()
    val opr2_sel = OprSel()

    val ps1_value = UInt(p.XLEN.W)
    val ps2_value = UInt(p.XLEN.W)

    val branch_taken = Bool()
    val predicted_next_pc = UInt(p.XLEN.W)

    val pdst = Valid(UInt(bl(p.PRF_DEPTH)))

    val rob_index = UInt(bl(p.ROB_DEPTH) + 1.W)
    val rob_inner_index = UInt(bl(p.CORE_WIDTH))
    
    // TODO
    val ghr = UInt(p.GHR_WIDTH.W)
}

class STISSUE_STPIPE_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 2
    val instr_ = UInt((p.XLEN-7).W)

    val ps1_value = UInt(p.XLEN.W)
    val ps2_value = UInt(p.XLEN.W)

    val stq_index = UInt(bl(p.STQ_DEPTH))
    val rob_index = UInt(bl(p.ROB_DEPTH) + 1.W)
    val rob_inner_index = UInt(bl(p.CORE_WIDTH))
}

class LDISSUE_LDPIPE_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 1
    val instr_ = UInt((p.XLEN-7).W)

    val ps1_value = UInt(p.XLEN.W)
    val pdst = Valid(UInt(bl(p.PRF_DEPTH)))
    val stq_tail = UInt(bl(p.STQ_DEPTH)) //used when stq forwarding

    val rob_index = UInt(bl(p.ROB_DEPTH) + 1.W)
    val rob_inner_index = UInt(bl(p.CORE_WIDTH))
}

// 所有FU的写回uop均相同，除了BU有额外的branch信号。BU信号通过EXU输出。
class WB_uop(implicit p: Parameters) extends uop {

    // Temporary 
    // val instr = UInt((p.XLEN-7).W)
    val pdst = Valid(UInt(bl(p.PRF_DEPTH))) // 对于大部分的FU来说，写回一般是有效的，但是有些指令是无需写回的。比如 Branch, ST指令无需写回
    val pdst_value = UInt(p.XLEN.W)

    //writeback to ROB
    val rob_index = UInt(bl(p.ROB_DEPTH) + 1.W)
    val rob_inner_index = UInt(bl(p.CORE_WIDTH))
}


class BU_signals(implicit p: Parameters) extends Bundle {
    // TODO: Temporary
    val is_conditional = Bool() //needed to distinguish between conditional branches and unconditional branches, 1 represents conditional branch

    // for updating branch predictor
    val instr_addr = UInt(p.XLEN.W)

    //writeback to ROB
    val mispred = Bool() //1 if mispred, 0 otherwise
    val target_PC = UInt(p.XLEN.W)
    val branch_taken = Bool()

    // Can be optimised
    // For checking age dependencies
    val branch_mask = UInt(p.BRANCH_MASK_WIDTH.W)
    // For updating branch_mask
    val branch_id = UInt(log2Ceil(p.BRANCH_MASK_WIDTH).W)
    val ghr = UInt(p.GHR_WIDTH.W)

    val rob_index = UInt(bl(p.ROB_DEPTH) + 1.W)
    val rob_inner_index = UInt(bl(p.CORE_WIDTH))
}

class InstrDebug(implicit p: Parameters) extends Bundle {
    val instr = UInt(p.XLEN.W)
    val pc = UInt(p.XLEN.W)

    def apply(): Unit = {
        this := DontCare 
    }

    def apply(payload: uop, valid: Bool): Unit = {
        this := RegNext(Mux(valid, payload.debug, 0.U.asTypeOf(this.cloneType)))
    }
    def apply(payload: Valid[uop]): Unit = {
        this := RegNext(Mux(payload.valid, payload.bits.debug, 0.U.asTypeOf(this.cloneType)))
    }
    def apply(payload: DecoupledIO[uop]): Unit = {
        this := RegNext(Mux(payload.valid, payload.bits.debug, 0.U.asTypeOf(this.cloneType)))
    }
    def apply(instr: UInt, pc: UInt, valid: Bool): Unit = {
        val temp = Wire(this.cloneType)
        temp.instr := instr
        temp.pc := pc
        this := Mux(valid, temp, 0.U.asTypeOf(this.cloneType))
    }
}

