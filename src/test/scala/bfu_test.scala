import chisel3._
import chiseltest.WriteVcdAnnotation

import scala.util.Random
import chisel3.stage.ChiselGeneratorAnnotation
import chiseltest._
import firrtl.options.TargetDirAnnotation
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.flatspec.AnyFlatSpec
import rsd_rv32.scheduler._
import rsd_rv32.common.Parameters
import rsd_rv32.common._
import chiseltest.testableClock
import rsd_rv32.execution.BranchFU
/**
 * This is a trivial example of how to run this Specification
 * From within sbt use:
 * {{{
 * testOnly gcd.GcdDecoupledTester
 * }}}
 * From a terminal shell use:
 * {{{
 * sbt 'testOnly gcd.GcdDecoupledTester'
 * }}}
 */

class bfu_test extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()

  object InstrHelper {
    def stripOpcode(instr: UInt): UInt = instr(31, 7)
  }

  "exu_issue" should "accept the instrs from dispatch unit and issue correctly" in {
    test(new BranchFU()) { c =>

      println("test pass")
    }
  }

  it should "correctly handle taken BEQ branch (no mispred)" in {
    test(new BranchFU()) { c =>
      val fullInstr = Instr.B(
        imm = 8.U,
        rs2 = 1.U,
        rs1 = 2.U,
        funct3 = "b000".U,
        opcode = "b1100011".U // BEQ
      )

      c.io.uop.valid.poke(true.B)
      c.io.uop.bits.instr.poke(InstrHelper.stripOpcode(fullInstr))
      c.io.uop.bits.instr_type.poke(InstrType.Branch)
      c.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      c.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)

      c.io.uop.bits.ps1_value.poke(10.U)
      c.io.uop.bits.ps2_value.poke(10.U)

      c.io.uop.bits.instr_addr.poke(100.U)
      c.io.uop.bits.branch_pred.poke(BranchPred.T)
      c.io.uop.bits.target_PC.poke(108.U) // branch target = 100 + 8
      c.io.uop.bits.pdst.poke(1.U)
      c.io.uop.bits.rob_index.poke(5.U)

      c.clock.step()

      c.out.valid.expect(true.B)
      c.out.bits.mispred.expect(false.B)
      c.out.bits.target_PC.expect(108.U)
      c.out.bits.branch_direction.expect(BranchPred.T)
      c.out.bits.pdst_value.expect(104.U) // PC + 4
    }
  }

  it should "detect misprediction when branch direction differs" in {
    test(new BranchFU()) { c =>
      val fullInstr = Instr.B(
        imm = 8.U,
        rs2 = 1.U,
        rs1 = 2.U,
        funct3 = "b000".U,
        opcode = "b1100011".U // BEQ
      )

      c.io.uop.valid.poke(true.B)
      c.io.uop.bits.instr.poke(InstrHelper.stripOpcode(fullInstr))
      c.io.uop.bits.instr_type.poke(InstrType.Branch)
      c.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      c.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)

      c.io.uop.bits.ps1_value.poke(10.U)
      c.io.uop.bits.ps2_value.poke(20.U) // not equal

      c.io.uop.bits.instr_addr.poke(100.U)
      c.io.uop.bits.branch_pred.poke(BranchPred.T)
      c.io.uop.bits.target_PC.poke(108.U)
      c.io.uop.bits.pdst.poke(1.U)
      c.io.uop.bits.rob_index.poke(6.U)

      c.clock.step()

      c.out.valid.expect(true.B)
      c.out.bits.mispred.expect(true.B)
      c.out.bits.branch_direction.expect(BranchPred.NT)
    }
  }

  it should "handle JAL instruction" in {
    test(new BranchFU()) { c =>
      val fullInstr = Instr.J(
        imm = 20.U, // jump forward 20
        rd = 1.U,
        opcode = "b1101111".U // JAL
      )

      c.io.uop.valid.poke(true.B)
      c.io.uop.bits.instr.poke(InstrHelper.stripOpcode(fullInstr))
      c.io.uop.bits.instr_type.poke(InstrType.Jump)
      c.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.PC) // jal 的 opr1 是 PC

      c.io.uop.bits.ps1_value.poke(0.U) // 不用
      c.io.uop.bits.ps2_value.poke(0.U)
      c.io.uop.bits.instr_addr.poke(100.U)
      c.io.uop.bits.branch_pred.poke(BranchPred.NT) // predict not taken
      c.io.uop.bits.target_PC.poke(104.U)
      c.io.uop.bits.pdst.poke(3.U)
      c.io.uop.bits.rob_index.poke(7.U)

      c.clock.step()

      c.out.valid.expect(true.B)
      c.out.bits.mispred.expect(true.B) // should have been taken
      c.out.bits.target_PC.expect(120.U) // 100 + 20
      c.out.bits.branch_direction.expect(BranchPred.T)
      c.out.bits.pdst_value.expect(104.U) // return address = PC + 4
    }
  }

  it should "handle JALR instruction" in {
    test(new BranchFU()) { c =>
      val fullInstr = Instr.I(
        imm = 4.U,
        rs1 = 1.U,
        funct3 = 0.U,
        rd = 1.U,
        opcode = "b1100111".U // JALR
      )

      c.io.uop.valid.poke(true.B)
      c.io.uop.bits.instr.poke(InstrHelper.stripOpcode(fullInstr))
      c.io.uop.bits.instr_type.poke(InstrType.Jump)
      c.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)

      c.io.uop.bits.ps1_value.poke(100.U)
      c.io.uop.bits.ps2_value.poke(0.U)
      c.io.uop.bits.instr_addr.poke(200.U)
      c.io.uop.bits.branch_pred.poke(BranchPred.NT)
      c.io.uop.bits.target_PC.poke(204.U)
      c.io.uop.bits.pdst.poke(4.U)
      c.io.uop.bits.rob_index.poke(8.U)

      c.clock.step()

      c.out.valid.expect(true.B)
      c.out.bits.mispred.expect(true.B) // should have been taken
      c.out.bits.target_PC.expect(104.U) // 100 + 4 aligned
      c.out.bits.pdst_value.expect(204.U) // return address
    }
  }
}

