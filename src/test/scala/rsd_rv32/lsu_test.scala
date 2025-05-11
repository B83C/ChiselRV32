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
  "lsu" should "correct" in {
    test(new LSU()) { c =>
      println("pass!")
    }
  }

}

