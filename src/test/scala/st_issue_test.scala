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
      dut.io.st_issue_uop.bits(0).valid.poke(1.U)
      dut.io.st_issue_uop.bits(0).bits.iq_index.poke(0.U)
      dut.io.st_issue_uop.bits(1).valid.poke(0.U)
      dut.clock.step()
      dut.io.st_issue_uop.bits(0).valid.poke(1.U)
      dut.io.st_issue_uop.bits(0).bits.iq_index.poke(2.U)
      dut.io.st_issue_uop.bits(1).valid.poke(1.U)
      dut.io.st_issue_uop.bits(1).bits.iq_index.poke(1.U)
      dut.io.st_issue_uop.bits(1).bits.ps1.poke(2.U)
      dut.io.st_issue_uop.bits(1).bits.ps2.poke(3.U)
      dut.clock.step(2)
      dut.io.wb_uop1(1).valid.poke(1.U)
      dut.io.wb_uop1(1).bits.pdst.poke(3.U)
      dut.io.wb_uop1(3).valid.poke(1.U)
      dut.io.wb_uop1(3).bits.pdst.poke(2.U)
      dut.clock.step(2)
      dut.io.store_uop.valid.expect(1.U)
      println("test pass")
    }
  }
  "st_issue" should "accept 2 instrs from dispatch unit and issue correctly" in {
    test(new st_issue_queue()) { dut =>
      //一次性接收两条条目
      println("the case that receive 2 content")
      dut.io.st_issue_uop.bits(0).valid.poke(1.U)
      dut.io.st_issue_uop.bits(1).valid.poke(1.U)
      dut.io.st_issue_uop.bits(0).bits.iq_index.poke(5.U)
      dut.io.st_issue_uop.bits(1).bits.iq_index.poke(6.U)
      dut.io.st_issue_uop.bits(0).bits.ps1.poke(2.U)
      dut.clock.step()
      dut.io.st_issue_uop.bits(0).valid.poke(0.U)
      dut.io.st_issue_uop.bits(1).valid.poke(0.U)
      dut.clock.step()
      dut.clock.step()
      dut.io.prf_valid(2).poke(1)
      dut.io.st_issue_uop.bits(0).valid.poke(1.U)
      dut.io.st_issue_uop.bits(0).bits.iq_index.poke(4.U)
      dut.clock.step(3)
      println("test pass")
    }
  }
  //检查发生误预测时是否能够flush
  "st_issue" should "flush correctly when mispred" in {
    test(new st_issue_queue()) { dut =>
      dut.io.st_issue_uop.bits(0).valid.poke(1.U)
      dut.io.st_issue_uop.bits(0).bits.iq_index.poke(3.U)
      dut.io.st_issue_uop.bits(1).valid.poke(1.U)
      dut.io.st_issue_uop.bits(1).bits.iq_index.poke(4.U)
      dut.clock.step()
      dut.io.st_issue_uop.bits(0).bits.iq_index.poke(2.U)
      dut.io.st_issue_uop.bits(1).bits.iq_index.poke(1.U)
      dut.clock.step()
      dut.io.st_issue_uop.bits(0).valid.poke(0.U)
      dut.io.st_issue_uop.bits(1).valid.poke(0.U)
      dut.io.rob_commitsignal(0).valid.poke(1.U)
      dut.io.rob_commitsignal(0).bits.mispred.poke(1.U)
      dut.clock.step(1)
      for (i <- 0 until 5){
        dut.io.queue(i).waiting.expect(0.U)
      }
      println("test pass")
    }
  }
}

