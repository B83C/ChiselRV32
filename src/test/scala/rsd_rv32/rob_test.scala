package rsd_rv32

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

class rob_test extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()
  "rob" should "correct" in {
    test(new ROB()) { c =>
      c.io.dis_uop(0).valid.poke(1.U)
      c.io.dis_uop(0).bits.instr_addr.poke("h0000ffff".U)
      c.io.dis_uop(0).bits.instr_type.poke(InstrType.ALU)
      c.io.dis_uop(0).bits.pdst.poke(2.U)
      c.io.dis_uop(0).bits.rd.poke(1.U)
      c.clock.step()
      c.io.dis_uop(0).valid.poke(0.U)
      c.clock.step()
      println("pass!")
    }
  }
  "rob" should "receive 2 uop correctly" in {
    test(new ROB()) { c =>
      c.io.dis_uop(0).valid.poke(1.U)
      c.io.dis_uop(0).bits.instr_addr.poke("h0000ffff".U)
      c.io.dis_uop(0).bits.instr_type.poke(InstrType.ALU)
      c.io.dis_uop(0).bits.pdst.poke(2.U)
      c.io.dis_uop(0).bits.rd.poke(1.U)

      c.io.dis_uop(1).valid.poke(1.U)
      c.io.dis_uop(1).bits.instr_addr.poke("h0000abcd".U)
      c.io.dis_uop(1).bits.instr_type.poke(InstrType.Branch)
      c.io.dis_uop(1).bits.btb_hit.poke(BTBHit.H)
      c.io.dis_uop(1).bits.GHR.poke(2.U)
      c.clock.step()
      c.io.dis_uop(0).valid.poke(1.U)
      c.io.dis_uop(1).valid.poke(0.U)

      c.io.dis_uop(0).bits.instr_addr.poke("h00001234".U)
      c.io.dis_uop(0).bits.instr_type.poke(InstrType.ST)
      c.clock.step()
      c.io.dis_uop(0).valid.poke(0.U)
      c.clock.step()
      println("pass!")
    }
  }
  "rob" should "update, retire and flush correctly" in {
    test(new ROB()) { c =>
      c.io.dis_uop(0).valid.poke(1.U)
      c.io.dis_uop(0).bits.instr_addr.poke("h0000ffff".U)
      c.io.dis_uop(0).bits.instr_type.poke(InstrType.ALU)
      c.io.dis_uop(0).bits.pdst.poke(2.U)
      c.io.dis_uop(0).bits.rd.poke(1.U)

      c.io.dis_uop(1).valid.poke(1.U)
      c.io.dis_uop(1).bits.instr_addr.poke("h0000abcd".U)
      c.io.dis_uop(1).bits.instr_type.poke(InstrType.Branch)
      c.io.dis_uop(1).bits.btb_hit.poke(BTBHit.H)
      c.io.dis_uop(1).bits.GHR.poke(2.U)
      c.clock.step()
      c.io.dis_uop(0).valid.poke(0.U)
      c.io.dis_uop(1).valid.poke(0.U)
      c.io.alu_wb_uop(1).valid.poke(1.U)
      c.io.alu_wb_uop(1).bits.rob_index.poke(0.U)

      c.clock.step()
      c.io.bu_wb_uop(0).valid.poke(1.U)
      c.io.bu_wb_uop(0).bits.rob_index.poke(1.U)
      c.io.bu_wb_uop(0).bits.mispred.poke(1.U)
      c.io.bu_wb_uop(0).bits.target_PC.poke("h0000aaaa".U)
      c.io.bu_wb_uop(0).bits.is_conditional.poke(1.U)
      c.io.bu_wb_uop(0).bits.branch_direction.poke(BranchPred.T)
      c.io.alu_wb_uop(0).valid.poke(0.U)
      c.io.rob_commitsignal(0).valid.expect(1.U)
      c.io.rob_commitsignal(1).valid.expect(0.U)
      c.clock.step()
      c.io.bu_wb_uop(0).valid.poke(0.U)
      c.clock.step(3)
      println("pass!")
    }
  }
  "rob" should "flush correctly" in {
    test(new ROB()) { c =>
      c.io.dis_uop(0).valid.poke(1.U)
      c.io.dis_uop(0).bits.instr_addr.poke("h0000abcd".U)
      c.io.dis_uop(0).bits.instr_type.poke(InstrType.Branch)
      c.io.dis_uop(0).bits.btb_hit.poke(BTBHit.H)
      c.clock.step()
      c.io.dis_uop(0).bits.instr_addr.poke("h0000aaaa".U)
      c.io.dis_uop(0).bits.instr_type.poke(InstrType.DIV_REM)
      c.io.dis_uop(0).bits.pdst.poke(2.U)
      c.clock.step()
      c.io.dis_uop(0).valid.poke(0.U)
      c.io.bu_wb_uop(0).valid.poke(1.U)
      c.io.bu_wb_uop(0).bits.rob_index.poke(0.U)
      c.io.bu_wb_uop(0).bits.mispred.poke(1.U)
      c.io.bu_wb_uop(0).bits.target_PC.poke("h0000abcd".U)
      c.io.bu_wb_uop(0).bits.is_conditional.poke(1.U)
      c.io.bu_wb_uop(0).bits.branch_direction.poke(BranchPred.T)
      c.clock.step(3)
      println("pass!")
    }
  }
  "rob" should "head and tail move correctly" in {
    test(new ROB()) { c =>
      c.io.dis_uop(0).valid.poke(1.U)
      c.io.dis_uop(0).bits.instr_type.poke(InstrType.ALU)
      c.io.dis_uop(0).bits.instr_addr.poke(1.U)
      c.io.dis_uop(1).valid.poke(1.U)
      c.io.dis_uop(1).bits.instr_type.poke(InstrType.ALU)
      c.io.dis_uop(1).bits.instr_addr.poke(1.U)
      c.clock.step(5)
      c.io.alu_wb_uop(0).valid.poke(1.U)
      c.io.alu_wb_uop(0).bits.rob_index.poke(0.U)
      c.io.alu_wb_uop(1).valid.poke(1.U)
      c.io.alu_wb_uop(1).bits.rob_index.poke(3.U)
      c.clock.step()
      println("after WB")
      c.io.alu_wb_uop(0).valid.poke(0.U)
      c.io.alu_wb_uop(0).valid.poke(0.U)
      c.clock.step(2)
      c.io.dis_uop(1).valid.poke(0.U)
      c.clock.step()
      c.io.dis_uop(0).valid.poke(0.U)
      c.clock.step(2)
      println("pass!")
    }
  }
}

