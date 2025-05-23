package rsd_rv32.common

import chisel3._
import chisel3.util._
import rsd_rv32.common._

package object Utils {
  def is_in[T, R](result: R, allowed: T*)(implicit value: T): Option[R] =
    if(allowed.contains(value)) Some(result) else None
  // def is[T, R, E](result: R, else_result: E, allowed: T*)(implicit value: T): Option[R] =
  //   if(allowed.contains(value)) result else E

  def immExtract(instr: UInt, instr_type: IType.Type) : UInt = {
    import IType._
    instr_type match {
      case I => instr(31,20)
      case S => Cat(instr(31,25),instr(11,7))
      case B => Cat(instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W))
      case U => Cat(instr(31, 12), 0.U(12.W))
      case J => Cat(instr(31), instr(19,12), instr(20), instr(30,21), 0.U(1.W))
    }
  }

  def isMispredicted(commitSignal: Valid[ROBContent]): Bool = {
    commitSignal.valid && commitSignal.bits.mispred
  }

  case class Request[T <: Data](data: T) extends Bundle {
    val bits = (data)
    val ready = Flipped(Bool())
  }

  class ReadValueRequest[T <: Data, Q <: Data](genT: T, genQ: Q) extends Bundle {
    val value = Flipped(genT.cloneType)
    val addr  = genQ.cloneType
    val valid = Bool()
    // override def cloneType: this.type = new ReadValueRequest(genT, genQ).asInstanceOf[this.type]
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
  // case class ReadValueRequest[T <: Data, Q <: Data](data: T, addr_type: Q) extends Bundle {
  //   val value = Flipped(data)
  //   val addr = addr_type
  //   val valid = Bool()
  // }
}
  
// Helper tool to generate RiscV instructions
object Instr {
  // R-type: funct7 | rs2 | rs1 | funct3 | rd | opcode

  def R(funct7: UInt = 0.U, rs2: UInt = 0.U, rs1: UInt = 0.U, funct3: UInt = 0.U, rd: UInt = 0.U, opcode: UInt = 0.U): UInt = {
    val value =
      ((funct7.litValue & 0x7fL) << 25) |
      ((rs2.litValue   & 0x1fL) << 20) |
      ((rs1.litValue   & 0x1fL) << 15) |
      ((funct3.litValue& 0x07L) << 12) |
      ((rd.litValue    & 0x1fL) << 7 ) |
      ((opcode.litValue& 0x7fL) << 0 )

    value.U(32.W)
  }

  def I(imm: UInt = 0.U, rs1: UInt = 0.U, funct3: UInt = 0.U, rd: UInt = 0.U, opcode: UInt = 0.U): UInt = {
    val value =
      ((imm.litValue     & 0xfffL) << 20) |
      ((rs1.litValue     & 0x1fL)  << 15) |
      ((funct3.litValue  & 0x07L)  << 12) |
      ((rd.litValue      & 0x1fL)  << 7 ) |
      ((opcode.litValue  & 0x7fL)  << 0 )

    value.U(32.W)
  }

  def S(imm: UInt = 0.U, rs2: UInt = 0.U, rs1: UInt = 0.U, funct3: UInt = 0.U, opcode: UInt = 0.U): UInt = {
    val immVal = imm.litValue & 0xfffL
    val immHi = (immVal >> 5) & 0x7fL
    val immLo = immVal & 0x1fL

    val value =
      (immHi               << 25) |
      ((rs2.litValue   & 0x1fL) << 20) |
      ((rs1.litValue   & 0x1fL) << 15) |
      ((funct3.litValue& 0x07L) << 12) |
      (immLo               << 7 ) |
      ((opcode.litValue& 0x7fL) << 0 )

    value.U(32.W)
  }

  def B(imm: UInt = 0.U, rs2: UInt = 0.U, rs1: UInt = 0.U, funct3: UInt = 0.U, opcode: UInt = 0.U): UInt = {
    val immVal = imm.litValue & 0x1fffL // 13-bit signed immediate

    val bit12 = (immVal >> 12) & 0x1L
    val bit11 = (immVal >> 11) & 0x1L
    val bits10_5 = (immVal >> 5) & 0x3fL
    val bits4_1 = (immVal >> 1) & 0xfL

    val value =
      (bit12               << 31) |
      (bits10_5            << 25) |
      ((rs2.litValue   & 0x1fL) << 20) |
      ((rs1.litValue   & 0x1fL) << 15) |
      ((funct3.litValue& 0x07L) << 12) |
      (bits4_1             << 8 ) |
      (bit11               << 7 ) |
      ((opcode.litValue& 0x7fL) << 0 )

    value.U(32.W)
  }

  def U(imm: UInt = 0.U, rd: UInt = 0.U, opcode: UInt = 0.U): UInt = {
    val value =
      ((imm.litValue     & 0xfffff000L)) | // already aligned
      ((rd.litValue      & 0x1fL) << 7 ) |
      ((opcode.litValue  & 0x7fL) << 0 )

    value.U(32.W)
  }

  def J(imm: UInt = 0.U, rd: UInt = 0.U, opcode: UInt = 0.U): UInt = {
    val immVal = imm.litValue & 0xfffffL // 20-bit signed immediate

    val bit20 = (immVal >> 20) & 0x1L
    val bits10_1 = (immVal >> 1) & 0x3ffL
    val bit11 = (immVal >> 11) & 0x1L
    val bits19_12 = (immVal >> 12) & 0xffL

    val value =
      (bit20               << 31) |
      (bits19_12           << 12) |
      (bit11               << 20) |
      (bits10_1            << 21) |
      ((rd.litValue    & 0x1fL) << 7 ) |
      ((opcode.litValue& 0x7fL) << 0 )

    value.U(32.W)
  }
  // Instruction without opcode
  def Truncate(instr: UInt) : UInt = {
    instr(31, 7)
  }

  case class DisplayInstr(
    instrType: InstrType.Type,
    rd: Option[Int] = None,
    rs1: Option[Int] = None,
    rs2: Option[Int] = None,
    funct3: Option[Int] = None,
    funct7: Option[Int] = None,
    imm: Option[BigInt] = None,
    raw: BigInt
  )

  def disassemble(instr: UInt, instrType: InstrType.Type): DisplayInstr = {
    val bits = instr.litValue

    def get(start: Int, len: Int): BigInt = (bits >> start) & ((BigInt(1) << len) - 1)

    val rd     = get(7, 5).toInt
    val funct3 = get(12, 3).toInt
    val rs1    = get(15, 5).toInt
    val rs2    = get(20, 5).toInt
    val funct7 = get(25, 7).toInt

    val display = instrType match {
      case InstrType.ALU | InstrType.MUL | InstrType.DIV_REM =>
        DisplayInstr(instrType, Some(rd), Some(rs1), Some(rs2), Some(funct3), Some(funct7), None, bits)

      case InstrType.Branch =>
        val imm = (get(31, 1) << 12) |
                  (get(7, 1)  << 11) |
                  (get(25, 6) << 5 ) |
                  (get(8, 4)  << 1 )
        DisplayInstr(instrType, None, Some(rs1), Some(rs2), Some(funct3), None, Some(imm), bits)

      case InstrType.Jump =>
        val imm = (get(31, 1) << 20) |
                  (get(12, 8) << 12) |
                  (get(20, 1) << 11) |
                  (get(21,10) << 1)
        DisplayInstr(instrType, Some(rd), None, None, None, None, Some(imm), bits)

      case InstrType.LD | InstrType.CSR =>
        val imm = get(20, 12)
        DisplayInstr(instrType, Some(rd), Some(rs1), None, Some(funct3), None, Some(imm), bits)

      case InstrType.ST =>
        val imm = (get(25, 7) << 5) | get(7, 5)
        DisplayInstr(instrType, None, Some(rs1), Some(rs2), Some(funct3), None, Some(imm), bits)
    }

    display
  }
}

object dbg {
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

object DebugRegNext {
  def apply[T <: Data](value: T, valid: Bool): T = {
    RegNext(Mux(valid, value, 0.U.asTypeOf(value.cloneType)))
  }
}
