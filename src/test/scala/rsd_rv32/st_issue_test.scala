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

class st_issue_test extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()
  "st_issue" should "accept the instrs from dispatch unit and issue correctly" in {
    test(new st_issue_queue()) { dut =>
      dut.io.issue_st_uop.ready.poke(1)
      dut.io.dis_uop(0).valid.poke(1.U)
      dut.io.dis_uop(0).bits.iq_index.poke(0.U)
      dut.io.dis_uop(1).valid.poke(0.U)
      dut.clock.step()
      dut.io.dis_uop(0).valid.poke(1.U)
      dut.io.dis_uop(0).bits.iq_index.poke(2.U)
      dut.io.dis_uop(1).valid.poke(1.U)
      dut.io.dis_uop(1).bits.iq_index.poke(1.U)
      dut.io.dis_uop(1).bits.ps1.poke(2.U)
      dut.io.dis_uop(1).bits.ps2.poke(3.U)
      dut.clock.step(2)
      dut.io.wb_uop1(1).valid.poke(1.U)
      dut.io.wb_uop1(1).bits.pdst.poke(3.U)
      dut.io.wb_uop2(3).valid.poke(1.U)
      dut.io.wb_uop2(3).bits.pdst.poke(2.U)
      dut.clock.step(3)
      println("test pass")
    }
  }
  "st_issue" should "accept 2 instrs from dispatch unit and issue correctly" in {
    test(new st_issue_queue()) { dut =>
      //一次性接收两条条目
      println("the case that receive 2 content")
      dut.io.dis_uop(0).valid.poke(1.U)
      dut.io.dis_uop(1).valid.poke(1.U)
      dut.io.dis_uop(0).bits.iq_index.poke(5.U)
      dut.io.dis_uop(1).bits.iq_index.poke(6.U)
      dut.io.dis_uop(0).bits.ps1.poke(2.U)
      dut.clock.step()
      dut.io.dis_uop(0).valid.poke(0.U)
      dut.io.dis_uop(1).valid.poke(0.U)
      dut.clock.step()
      dut.clock.step()
      dut.io.prf_valid(2).poke(1)
      dut.io.dis_uop(0).valid.poke(1.U)
      dut.io.dis_uop(0).bits.iq_index.poke(4.U)
      dut.clock.step(3)
      println("test pass")
    }
  }
  //检查发生误预测时是否能够flush
  "exu_issue" should "flush correctly when mispred" in {
    test(new exu_issue_queue()) { dut =>
      dut.io.mul_ready.poke(0.U)
      dut.io.div_ready.poke(0.U)
      dut.io.dis_uop(0).valid.poke(1.U)
      dut.io.dis_uop(0).bits.iq_index.poke(3.U)
      dut.io.dis_uop(0).bits.instr_type.poke(InstrType.MUL)
      dut.io.dis_uop(1).valid.poke(1.U)
      dut.io.dis_uop(1).bits.iq_index.poke(4.U)
      dut.io.dis_uop(1).bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.clock.step()
      dut.io.dis_uop(0).bits.iq_index.poke(2.U)
      dut.io.dis_uop(0).bits.instr_type.poke(InstrType.Branch)
      dut.io.dis_uop(0).bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.dis_uop(1).bits.iq_index.poke(1.U)
      dut.io.dis_uop(1).bits.instr_type.poke(InstrType.DIV_REM)
      dut.clock.step()
      dut.io.dis_uop(0).valid.poke(0.U)
      dut.io.dis_uop(1).valid.poke(0.U)
      dut.io.rob_commitsignal(0).valid.poke(1.U)
      dut.io.rob_commitsignal(0).bits.mispred.poke(1.U)
      dut.clock.step(2)
      for (i <- 0 until 5){
        dut.io.queue(i).busy.expect(0.U)
      }
      println("test pass")
    }
  }
}

