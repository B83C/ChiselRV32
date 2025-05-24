import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import rsd_rv32.common._
import rsd_rv32.scheduler._
import rsd_rv32.execution._

import rsd_rv32.common._

import Instr._

class CSRFUTest extends AnyFreeSpec with Matchers with CSRConsts {
  implicit val p = new Parameters()
  // TODO: Need some way to customise the address, currently it is fixed to that in Parameters
  val mtime_addr = p.CSR_MTIME_ADDR
  val mcycle_addr = p.CSR_MCYCLE_ADDR
  "Normal CSRRW" in {
    simulate(new CSRFU_Default()) { c =>
      val next_clock = scala.util.Random.nextInt(1 << 30)
      val next_pdst = scala.util.Random.nextInt(1 << 5)
      val next_clock_imm = scala.util.Random.nextInt(1 << 5)

      c.io.reset.poke(true.B)
      c.clock.step()
      c.io.reset.poke(false.B)

      println("CSR Reset!")

      c.io.uop.valid.poke(true.B)
      c.io.uop.bits.instr_.poke(Truncate(I(
        imm = mtime_addr.U,
        rs1 = 1.U,
        funct3 = CSRRW, 
        rd = 3.U,
      )))
      c.io.uop.bits.instr_type.poke(InstrType.CSR)
      c.io.uop.bits.ps1_value.poke(next_clock)
      c.io.uop.bits.pdst.poke(next_pdst.U)
      c.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      c.clock.step()
      c.io.uop.valid.poke(false.B)
      c.out.bits.pdst_value.expect(0.U)
      c.out.bits.pdst.expect(next_pdst.U)

      val next_clock_1 = scala.util.Random.nextInt(1 << 30)
      c.io.uop.bits.ps1_value.poke(next_clock_1.U)
      c.io.uop.valid.poke(true.B)
      c.clock.step()
      c.io.uop.valid.poke(false.B)
      c.out.bits.pdst_value.expect(next_clock.U) // Last Result
      c.out.bits.pdst.expect(next_pdst.U)

      c.io.uop.valid.poke(true.B)
      c.io.uop.bits.ps1_value.poke(0x1234.U) //Should not go through
      c.io.uop.bits.instr_.poke(Truncate(I(
        imm = mtime_addr.U,
        rs1 = next_clock_imm.U,
        funct3 = CSRRWI, 
        rd = 3.U,
      )))
      c.io.uop.bits.pdst.poke(4.U)
      c.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.IMM)
      c.clock.step()
      c.io.uop.valid.poke(false.B)
      c.out.bits.pdst_value.expect(next_clock_1.U) // Last Result
      c.out.bits.pdst.expect(4.U)

      println(s"immediate: ${next_clock_imm}")
      c.io.uop.valid.poke(true.B)
      c.clock.step()
      c.out.bits.pdst.expect(4.U)
      c.out.bits.pdst_value.expect(next_clock_imm.U) // Last Result
      c.io.uop.valid.poke(false.B)

      c.clock.step()
      println("Should end here")

    }
  }
  "CSRRW rd 0 should not read" in {

    simulate(new CSRFU_Default()) { c =>
      val next_clock = scala.util.Random.nextInt(1 << 30)
      val next_pdst = scala.util.Random.nextInt(1 << 5)

      c.io.reset.poke(true.B)
      c.clock.step()
      c.io.reset.poke(false.B)

      println(s"rd0 starts here")
      c.io.uop.valid.poke(true.B)
      c.io.uop.bits.instr_.poke(Truncate(I(
        imm = mtime_addr.U,
        rs1 = 1.U,
        funct3 = CSRRW, 
        rd = 0.U,
        opcode = 0.U,
      )))
      c.io.uop.bits.pdst.poke(0.U)
      c.io.uop.bits.instr_type.poke(InstrType.CSR)
      c.io.uop.bits.ps1_value.poke(next_clock)
      c.io.uop.bits.instr_addr.poke(0.U)
      c.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      c.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.IMM)
      c.io.uop.bits.ps2_value.poke(0.U)
      c.io.uop.bits.branch_pred.poke(BranchPred.NT)
      c.io.uop.bits.target_PC.poke(0.U)
      c.clock.step()
      c.io.uop.valid.poke(false.B)
      c.out.valid.expect(false.B)

      c.io.uop.bits.instr_.poke(Truncate(I(
        imm = mtime_addr.U,
        rs1 = 1.U,
        funct3 = CSRRW, 
        rd = next_pdst.U,
        opcode = 0.U,
      )))
      c.io.uop.bits.pdst.poke(next_pdst.U)
      c.io.uop.valid.poke(true.B)
      c.clock.step()
      c.io.uop.valid.poke(false.B)
      // Result from last poke
      c.out.bits.pdst_value.expect(next_clock.U)
      c.out.bits.pdst.expect(next_pdst.U)
      c.out.valid.expect(true.B)

      c.clock.step()
    }
  }
  "CSRRS | CSRRC should set and clear correctly" in {
    simulate(new CSRFU_Default()) { c =>
      val next_clock = 1 << scala.util.Random.nextInt(30)
      val next_pdst = scala.util.Random.nextInt(1 << 5)

      c.io.reset.poke(true.B)
      c.clock.step()
      c.io.reset.poke(false.B)

      c.io.uop.valid.poke(true.B)
      c.io.uop.bits.instr_.poke(Truncate(I(
        imm = mtime_addr.U,
        rs1 = 1.U,
        funct3 = CSRRS, 
        rd = 2.U, //Anything not 0
        opcode = 0.U,
      )))
      c.io.uop.bits.pdst.poke(2.U)
      c.io.uop.bits.instr_type.poke(InstrType.CSR)
      c.io.uop.bits.ps1_value.poke(next_clock)
      c.io.uop.bits.instr_addr.poke(0.U)
      c.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.IMM)
      c.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.IMM)
      c.io.uop.bits.ps2_value.poke(0.U)
      c.io.uop.bits.branch_pred.poke(BranchPred.NT)
      c.io.uop.bits.target_PC.poke(0.U)
      c.clock.step()
      c.io.uop.valid.poke(false.B)
      c.out.bits.pdst_value.expect(0.U)
      c.out.bits.pdst.expect(2.U)
      c.out.valid.expect(true.B)

      c.io.uop.bits.instr_.poke(Truncate(I(
        imm = mtime_addr.U,
        rs1 = 1.U,
        funct3 = CSRRS, 
        rd = 2.U,
        opcode = 0.U,
      )))
      c.io.uop.bits.pdst.poke(2.U)
      c.io.uop.valid.poke(true.B)
      c.clock.step()
      c.io.uop.valid.poke(false.B)
      // Result from last poke
      c.out.bits.pdst_value.expect(next_clock.U)
      c.out.bits.pdst.expect(2.U)
      c.out.valid.expect(true.B)

      c.clock.step()
    }
  }
}
