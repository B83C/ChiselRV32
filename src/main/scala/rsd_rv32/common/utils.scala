package rsd_rv32.common

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.scheduler._

object RegEnableValid{
  def apply[T <: Data](bits: T, valid: Bool) : Valid[T] = {
    val output = Wire(Valid(bits.cloneType))
    output.bits := RegEnable(bits, valid)
    output.valid := RegNext(valid)
    output
  }
  def apply[T <: Data](bits: Valid[T]) : Valid[T] = {
    this.apply(bits.bits, bits.valid)
  }
}

object ValidPassthrough{
  def apply[T <: Data](bits: T, valid: Bool) : Valid[T] = {
    val output = Wire(Valid(bits.cloneType))
    output.bits := bits
    output.valid := valid
    output
  }
}

package object Utils {
  def is_in[T, R](result: R, allowed: T*)(implicit value: T): Option[R] =
    if(allowed.contains(value)) Some(result) else None
  // def is[T, R, E](result: R, else_result: E, allowed: T*)(implicit value: T): Option[R] =
  //   if(allowed.contains(value)) result else E

  def immExtract(instr: UInt, instr_type: IType.Type) : SInt = {
    import IType._
    instr_type match {
      case I => instr(31,20).asSInt.pad(32)
      case S => Cat(instr(31,25),instr(11,7)).asSInt.pad(32)
      case B => Cat(instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W)).asSInt.pad(32)
      case U => Cat(instr(31, 12), 0.U(12.W)).asSInt.pad(32)
      case J => Cat(instr(31), instr(19,12), instr(20), instr(30,21), 0.U(1.W)).asSInt.pad(32)
    }
  }

  // def isMispredicted(commitSignal: Valid[ROBContent]): Bool = {
  //   commitSignal.valid && commitSignal.bits.mispred
  // }

  case class Request[T <: Data](data: T) extends Bundle {
    val bits = (data)
    val ready = Flipped(Bool())
  }

  class ReadValueRequest[T <: Data, Q <: Data](genT: T, genQ: Q) extends Bundle {
    val value = Flipped(genT.cloneType)
    val addr  = genQ.cloneType
    val valid = Bool()
  }

  object ReadValueRequest {
    def apply[T <: Data, Q <: Data](genT: T, genQ: Q): ReadValueRequest[T, Q] =
      new ReadValueRequest(genT, genQ)

    def apply[T <: Data, Q <: Data](value: T, addr: Q, valid: Bool): ReadValueRequest[T, Q] = {
        val req = Wire(new ReadValueRequest(value.cloneType, addr.cloneType))
        value := req.value
        req.addr := addr
        req.valid := valid
        req
    }
  }
}
  
// Helper tool to generate RiscV instructions
object Instr {
  // Instruction without opcode
  def Truncate(instr: UInt) : UInt = {
    instr(31, 7)
  }

  case class DisplayInstr(
    opcode: UInt = 0.U,
    rd: UInt = 0.U,
    rs1: UInt = 0.U,
    rs2: UInt = 0.U,
    funct3: UInt = 0.U,
    funct7: UInt = 0.U,
    imm: UInt = 0.U(32.W)
  )

  def disassemble(instr: UInt, instr_type: IType.Type = IType.I): DisplayInstr = {

    val opcode = instr(6, 0)
    val rd     = instr(11, 7)
    val funct3 = instr(14, 12)
    val rs1    = instr(19, 15)
    val rs2    = instr(24, 20)
    val funct7 = instr(31, 25)

    val imm = instr_type match {
      case IType.R => 0.S(32.W)
      case IType.I => instr(31,20).asSInt.pad(32)
      case IType.S => Cat(instr(31,25),instr(11,7)).asSInt.pad(32)
      case IType.B => Cat(instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W)).asSInt.pad(32)
      case IType.U => Cat(instr(31, 12), 0.U(12.W)).asSInt.pad(32)
      case IType.J => Cat(instr(31), instr(19,12), instr(20), instr(30,21), 0.U(1.W)).asSInt.pad(32)
    }
    DisplayInstr(opcode, rd, rs1, rs2, funct3, funct7, imm.asUInt)
  }
}

object dbg {
  var enabled = false  // mutable for runtime switching (in sim), or keep as val for compile-time

  def apply(fmt: Printable)(implicit cond: Bool = true.B): Unit = {
    if (enabled) {
      // printf emits only when cond is true
      when(cond) {
        printf(fmt)
      }
    }
  }
}

object info {
  var enabled = true  // mutable for runtime switching (in sim), or keep as val for compile-time

  def apply(fmt: Printable)(implicit cond: Bool = true.B): Unit = {
    if (enabled) {
      // printf emits only when cond is true
      when(cond) {
        printf(fmt)
      }
    }
  }
}
