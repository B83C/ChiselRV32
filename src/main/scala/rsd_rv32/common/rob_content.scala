package rsd_rv32.common

import chisel3._
import chisel3.util._
import rsd_rv32.common.common._
import rsd_rv32.common.uop._

object ROBType extends ChiselEnum {
    val Arithmetic, Branch, Jump, Store, CSR = Value
}

class ROB_Arithmetic(implicit p: Parameters) extends Bundle {
    val pdst = UInt(p.PRF_DEPTH.W) // Physical destination register
    val rd = UInt(p.XLEN.W) // Destination register
}

object ROB_Arithmetic {
    def width(implicit p: Parameters): Int = (new ROB_Arithmetic).getWidth
}

class ROB_Branch(implicit p: Parameters) extends Bundle {
    val btb_hit = BTBHit() // BTB hit flag
    val target_PC = UInt(p.XLEN.W) // Target address
    val branch_direction = Bool() // Branch direction(1 represents taken)
    val GHR = UInt(p.GHR_WIDTH.W) // Global history register
}

object ROB_Branch {
    def width(implicit p: Parameters): Int = (new ROB_Branch).getWidth
}

class ROB_Jump(implicit p: Parameters) extends Bundle {
    val btb_hit = BTBHit() // BTB hit flag
    val target_PC = UInt(p.XLEN.W) // Target address
    val pdst = UInt(p.PRF_DEPTH.W) // Physical destination register
    val rd = UInt(p.XLEN.W) // Destination register
}

object ROB_Jump {
    def width(implicit p: Parameters): Int = (new ROB_Jump).getWidth
}

class ROB_Store(implicit p: Parameters) extends Bundle {

}

object ROB_Store {
    def width(implicit p: Parameters): Int = (new ROB_Store).getWidth
}

class ROB_CSR(implicit p: Parameters) extends Bundle {
    val pdst = UInt(p.PRF_DEPTH.W) // Physical destination register
    val rd = UInt(p.XLEN.W) // Destination register
}

object ROB_CSR {
    def width(implicit p: Parameters): Int = (new ROB_CSR).getWidth
}

object Payload {
    def width(implicit p: Parameters): Int = (ROB_Arithmetic.width max ROB_Branch.width max ROB_Jump.width max ROB_Store.width max ROB_CSR.width)
}

class ROBContent(implicit p: Parameters) extends Bundle {
    val instr_addr = UInt(p.XLEN.W) // Instruction address
    val rob_type = ROBType() // Instruction type
    val mispred = Bool() // Misprediction flag(1 represents misprediction)
    val completed = Bool() // Completion flag(1 represents completion)

    val payload = UInt(Payload.width.W) // Payload of the ROB entry
    def as_Arithmetic: ROB_Arithmetic = payload.asTypeOf(new ROB_Arithmetic)
    def as_Branch: ROB_Branch = payload.asTypeOf(new ROB_Branch)
    def as_Jump: ROB_Jump = payload.asTypeOf(new ROB_Jump)
    //def as_Store: ROB_Store = payload.asTypeOf(new ROB_Store)
    def as_CSR: ROB_CSR = payload.asTypeOf(new ROB_CSR)
}

object ROBContent {
    def width(implicit p: Parameters): Int = (new ROBContent).getWidth
}