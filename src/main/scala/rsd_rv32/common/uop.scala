package rsd_rv32.common

import chisel3._
import chisel3.util._

object InstrType extends ChiselEnum {
    val ALU, Branch, Jump, LD, ST, CSR, MUL, DIV_REM = Value
}

/*object JumpType extends ChiselEnum {
    val JAL, JALR = Value
}*/


object OprSel extends ChiselEnum {
    val IMM, REG, PC, Z = Value
}

class FUSignals extends Bundle {
    //val req_immediate = Bool()
    val opr1_sel = OprSel()
    val opr2_sel = OprSel()
}

object FUSignals {
    val width : Int = (new FUSignals).getWidth
}

/*class BUSignals extends Bundle {
    val jump_type = JumpType()
}

object BUSignals {
    val width : Int = (new BUSignals).getWidth
}*/

/*class FUSignals extends Bundle {
    val bits = UInt((ALUSignals.width max BUSignals.width).W)
    def as_ALU : ALUSignals = bits.asTypeOf(new ALUSignals)
    def as_BU : BUSignals = bits.asTypeOf(new BUSignals)
}*/

// abstract class BaseUOP()(implicit p: Parameters) extends Bundle {
//     val instr = UInt((p.XLEN-7).W) //func3, func7, rd, rs1 , rs2, imm without opcode;
// }

// Will be removed!
// abstract trait HasUOP extends CustomBundle {
//     val uop = new uop()
// }

// Will be removed!
// class uop(implicit p: Parameters) extends CustomBundle {
//     val instr = UInt((32 - 7).W) //func3, func7, rd, rs1 , rs2, imm without opcode
//     val instr_type = InstrType()
//     val instr_addr = UInt(p.XLEN.W)
//     val target_PC = UInt(p.XLEN.W)
//     val GHR = UInt(p.GHR_WIDTH.W)
//     // Signals created in the middle of the pipeline
//     val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
//     val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W)
//     val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W)
//     val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
//     val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)
//     val branch_taken = Bool()
//     val btb_hit = Bool()
    
//     val fu_signals = new FUSignals() //opcode is compiled into fu specific control signals
// }

// trait UopBase extends CustomBundle {
//     def transformTo[T<: UopBase](implicit transformer: UopTransformer[this.type, T]): T = {
//         transformer.transform(this)
//     }
// }

// trait UopTransformer[A <: UopBase, B <: UopBase] {
//     def transform(a: A) : B
// }

// object UopTransformer {
//   implicit def defaultTransformer[A <: UopBase, B <: UopBase](implicit genB: => B): UopTransformer[A, B] = new UopTransformer[A, B] {
//     def transform(a: A): B = {
//       val b = Wire(genB)
//       (b: Data).waiveAll :<>= (a: Data).waiveAll
//       b
//     }
//   }
// }

// object conversion {
//     implicit object IF_ID extends UopTransformer[IF_ID_uop, ID_RENAME_uop]
// }

// import conversion._

abstract class uop(implicit p: Parameters) extends CustomBundle {
    def prf_read_counts: Int = 0
    // Debugging
    val debug = new InstrDebug
} 

class IF_ID_uop(implicit p: Parameters) extends uop {
    val instr = UInt(p.XLEN.W) 
    val instr_addr = UInt(p.XLEN.W) //needed by rob, BU, ALU
    val predicted_next_pc = UInt(p.XLEN.W) //needed by BU
    val ghr = UInt(p.GHR_WIDTH.W) //needed by rob
    val branch_taken = Bool() //needed by BU
}

class ID_RENAME_uop(implicit p: Parameters) extends uop {

    //opcode is compiled into fu specific control signals
    val instr_type = InstrType() 
    val instr_ = UInt((p.XLEN-7).W) //func3, func7, rd, rs1 , rs2, imm without opcode
    val fu_signals = new FUSignals() 

    val instr_addr = UInt(p.XLEN.W)
    val predicted_next_pc = UInt(p.XLEN.W)
    val ghr = UInt(p.GHR_WIDTH.W)
    val branch_taken = Bool()
    // val btb_hit = BTBHit()
}

//继承ID_RENAME的uop
class RENAME_DISPATCH_uop(implicit p: Parameters) extends ID_RENAME_uop {
    override def prf_read_counts: Int = 2
    val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W)
}

class DISPATCH_ROB_uop(implicit p: Parameters) extends uop {
    val instr_addr = UInt(p.XLEN.W)

    val instr_type = InstrType()
    //val fu_signals = new FUSignals() //needed to distinguish between conditional branches and unconditioal ones

    val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
    val rd = UInt(5.W)

    val ghr = UInt(p.GHR_WIDTH.W)
    //val branch_pred = BranchPred()
    // val btb_hit = BTBHit()
    
    //val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    //val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)
}

class DISPATCH_EXUISSUE_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 2
    val instr_ = UInt((p.XLEN-7).W) //EXU need it to get imm,func,etc
    val instr_addr = UInt(p.XLEN.W)

    val instr_type = InstrType()
    val fu_signals = new FUSignals()

    val branch_taken = Bool()
    val predicted_next_pc = UInt(p.XLEN.W)

    val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W)

    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)

    val iq_index = UInt(log2Ceil(p.EXUISSUE_DEPTH).W)
}

class DISPATCH_LDISSUE_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 1
    val instr_ = UInt((p.XLEN-7).W) //Load Pipeline need it to get imm,func,etc
    
    val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val stq_tail = UInt(log2Ceil(p.STQ_DEPTH).W) //needed to get the right forwarding data from STQ

    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)

    val iq_index = UInt(log2Ceil(p.LDISSUE_DEPTH).W)
}

class DISPATCH_STISSUE_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 2
    val instr_ = UInt((p.XLEN-7).W) //Store Pipeline need it to get imm,func
    
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W)
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W)

    val stq_index = UInt(log2Ceil(p.STQ_DEPTH).W) //needed to writeback to STQ
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)

    val iq_index = UInt(log2Ceil(p.STISSUE_DEPTH).W)
}

class EXUISSUE_EXU_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 2
    val instr_ = UInt((p.XLEN-7).W)
    val instr_addr = UInt((p.XLEN).W)

    val instr_type = InstrType()
    val fu_signals = new FUSignals() //opcode is compiled into fu specific control signals

    val ps1_value = UInt(p.XLEN.W)
    val ps2_value = UInt(p.XLEN.W)

    val branch_taken = Bool()
    val predicted_next_pc = UInt(p.XLEN.W)

    val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)

    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)
    
}

class STISSUE_STPIPE_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 2
    val instr_ = UInt((p.XLEN-7).W)

    val ps1_value = UInt(p.XLEN.W)
    val ps2_value = UInt(p.XLEN.W)

    val stq_index = UInt(log2Ceil(p.STQ_DEPTH).W)
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)
}

class LDISSUE_LDPIPE_uop(implicit p: Parameters) extends uop {
    override def prf_read_counts: Int = 1
    val instr_ = UInt((p.XLEN-7).W)

    val ps1_value = UInt(p.XLEN.W)
    val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
    val stq_tail = UInt(log2Ceil(p.STQ_DEPTH).W) //used when stq forwarding

    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)
}

/*class WB_uop(implicit p: Parameters) extends uop {
    //writeback to PRF
    val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
    val pdst_value = UInt(p.XLEN.W)

    //writeback to ROB
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)
}*/

// 所有FU的写回uop均相同，除了BU有额外的branch信号。BU信号通过EXU输出。
class WB_uop(implicit p: Parameters) extends uop {

    // Temporary 
    // val instr = UInt((p.XLEN-7).W)
    val pdst = UInt(log2Ceil(p.PRF_DEPTH).W) // 对于大部分的FU来说，写回一般是有效的，但是有些指令是无需写回的。比如 Branch, ST指令无需写回
    val pdst_value = Valid(UInt(p.XLEN.W))

    //writeback to ROB
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)
}


class BU_uop(implicit p: Parameters) extends Bundle {
    // TODO: Temporary
    val is_conditional = Bool() //needed to distinguish between conditional branches and unconditional branches, 1 represents conditional branch

    //writeback to ROB
    val mispred = Bool() //1 if mispred, 0 otherwise
    val target_PC = UInt(p.XLEN.W)
    val branch_taken = Bool()

    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)
    //jal and jalr need to writeback to PRF
    // val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
    // val pdst_value = UInt(p.XLEN.W)
}

/*class LDPIPE_WB_uop(implicit p: Parameters) extends uop {
    //writeback to PRF
    val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
    val pdst_value = UInt(p.XLEN.W)

    //writeback to ROB
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)
}*/

class STPIPE_WB_uop(implicit p: Parameters) extends uop {
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    val rob_inner_index = UInt(log2Ceil(p.CORE_WIDTH).W)
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
        this := RegNext(Mux(valid, temp, 0.U.asTypeOf(this.cloneType)))
    }
}

