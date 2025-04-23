package rsd_rv32.common

import chisel3._
import chisel3.util._
import rsd_rv32.common.common._

Object ROBType extends ChiselEnum {
    val Arithmetic, Branch, Jump, Store, CSR = Value
}

class ROB_base(implicit p: Parameters) extends Bundle {
    val instrAddr = UInt(p.XLEN.W) // Instruction address
    val instrType = ROBType() // Instruction type
    val mispred = Bool() // Misprediction flag(1 represents misprediction)
    val completed = Bool() // Completion flag(1 represents completion)
}

class ROB_Arithmetic(implicit p: Parameters) extends ROB_base {
    val pdst = UInt(p.PRF_DEPTH.W) // Physical destination register
    val rd = UInt(p.XLEN.W) // Destination register
}

object ROB_Arithmetic {
    val width: Int = (new ROB_Arithmetic).getWidth
}

class ROB_Branch(implicit p: Parameters) extends ROB_base {
    val btb_hit = BTBHit() // BTB hit flag
    val branch_direction = Bool() // Branch direction(1 represents taken)
    val PC_target = UInt(p.XLEN.W) // Target address
    val GHR = UInt(p.GHR_WIDTH.W) // Global history register
}

object ROB_Branch {
    val width: Int = (new ROB_Branch).getWidth
}

class ROB_Jump(implicit p: Parameters) extends ROB_base {
    val pdst = UInt(p.PRF_DEPTH.W) // Physical destination register
    val rd = UInt(p.XLEN.W) // Destination register
    val PC_target = UInt(p.XLEN.W) // Target address
    val btb_hit = BTBHit() // BTB hit flag
}

object ROB_Jump {
    val width: Int = (new ROB_Jump).getWidth
}

class ROB_Store(implicit p: Parameters) extends ROB_base {

}

object ROB_Store {
    val width: Int = (new ROB_Store).getWidth
}

class ROB_CSR(implicit p: Parameters) extends ROB_base {
    val rd = UInt(p.XLEN.W) // Destination register
    val pdst = UInt(p.PRF_DEPTH.W) // Physical destination register
}

object ROB_CSR {
    val width: Int = (new ROB_CSR).getWidth
}

class ROBContent(implicit p: Parameters) extends Bundle {
    val bits = UInt((ROB_Arithmetic.width max ROB_Branch.width max ROB_Jump.width max ROB_Store.width max ROB_CSR.width).W)
    def as_Arithmetic: ROB_Arithmetic = bits.asTypeOf(new ROB_Arithmetic)
    def as_Branch: ROB_Branch = bits.asTypeOf(new ROB_Branch)
    def as_Jump: ROB_Jump = bits.asTypeOf(new ROB_Jump)
    def as_Store: ROB_Store = bits.asTypeOf(new ROB_Store)
    def as_CSR: ROB_CSR = bits.asTypeOf(new ROB_CSR)
}