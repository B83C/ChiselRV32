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
import rsd_rv32.execution.LSU
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

class lsu_test extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()
  "lsu" should "ptr move correctly" in {
    test(new LSU()) { c =>
      c.io.st_dis(0).poke(1.U)
      c.io.st_dis(1).poke(1.U)
      c.clock.step()
      c.io.st_dis(1).poke(0.U)
      c.clock.step()
      c.io.st_dis(0).poke(0.U)
      c.clock.step()
      c.io.rob_commitsignal(1).valid.poke(1.U)
      c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Store)
      c.io.rob_commitsignal(0).valid.poke(1.U)
      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Arithmetic)
      c.clock.step()
      c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Store)
      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Store)
      c.clock.step()
      c.io.rob_commitsignal(1).valid.poke(0.U)
      c.io.rob_commitsignal(0).valid.poke(0.U)
      c.clock.step(3)
      println("pass!")
    }
  }

}

