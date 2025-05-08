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

class exu_issue_test extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()
  "exu_issue" should "accept the instrs from dispatch unit" in {
    test(new exu_issue_queue()) { dut =>
      dut.io.dis_uop(0).valid.poke(1.U)
      dut.io.dis_uop(0).bits.iq_index.poke(2.U)
      dut.io.dis_uop(0).bits.instr_type.poke(InstrType.ALU)
      dut.io.dis_uop(1).valid.poke(0.U)
      dut.io.dis_uop(1).bits.iq_index.poke(1.U)
      dut.clock.step()
      dut.io.queue(2).busy.expect(1.U)
      dut.io.queue(1).busy.expect(0.U)
      dut.clock.step()
      dut.io.queue(2).busy.expect(0.U)
      println("test pass")
    }
  }
}