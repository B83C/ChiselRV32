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
import rsd_rv32.execution.PRF_Value
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
class prf_test extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()

  it should "test basic read and write operations to PRF_Value" in {
    test(new PRF_Value()) { dut =>
      // Helper function to write to a PRF register
      def writePRF(addr: UInt, data: UInt, index: Int): Unit = {
        if (index == 0) {
          dut.io.alu_wb_uop(0).valid.poke(true.B)
          dut.io.alu_wb_uop(0).bits.pdst.poke(addr)
          dut.io.alu_wb_uop(0).bits.pdst_value.poke(data)
        } else if (index == 1) {
          dut.io.alu_wb_uop(1).valid.poke(true.B)
          dut.io.alu_wb_uop(1).bits.pdst.poke(addr)
          dut.io.alu_wb_uop(1).bits.pdst_value.poke(data)
        } else if (index == 2) {
          dut.io.bu_wb_uop(0).valid.poke(true.B)
          dut.io.bu_wb_uop(0).bits.pdst.poke(addr)
          dut.io.bu_wb_uop(0).bits.pdst_value.poke(data)
        } else if (index == 3) {
          dut.io.mul_wb_uop(0).valid.poke(true.B)
          dut.io.mul_wb_uop(0).bits.pdst.poke(addr)
          dut.io.mul_wb_uop(0).bits.pdst_value.poke(data)
        } else if (index == 4) {
          dut.io.divrem_wb_uop(0).valid.poke(true.B)
          dut.io.divrem_wb_uop(0).bits.pdst.poke(addr)
          dut.io.divrem_wb_uop(0).bits.pdst_value.poke(data)
        } else if (index == 5) {
          dut.io.ldu_wb_uop(0).valid.poke(true.B)
          dut.io.ldu_wb_uop(0).bits.pdst.poke(addr)
          dut.io.ldu_wb_uop(0).bits.pdst_value.poke(data)
        } else if (index == 6) {
          dut.io.divrem_wb_uop(0).valid.poke(true.B)
          dut.io.divrem_wb_uop(0).bits.pdst.poke(addr)
          dut.io.divrem_wb_uop(0).bits.pdst_value.poke(data)
        } else if (index == 7) {
          dut.io.divrem_wb_uop(1).valid.poke(true.B)
          dut.io.divrem_wb_uop(1).bits.pdst.poke(addr)
          dut.io.divrem_wb_uop(1).bits.pdst_value.poke(data)
        }
        dut.clock.step()
        if (index == 0) dut.io.alu_wb_uop(0).valid.poke(false.B)
        if (index == 1) dut.io.alu_wb_uop(1).valid.poke(false.B)
        if (index == 2) dut.io.bu_wb_uop(0).valid.poke(false.B)
        if (index == 3) dut.io.bu_wb_uop(1).valid.poke(false.B)
        if (index == 4) dut.io.mul_wb_uop(0).valid.poke(false.B)
        if (index == 5) dut.io.mul_wb_uop(1).valid.poke(false.B)
        if (index == 6) dut.io.divrem_wb_uop(0).valid.poke(false.B)
        if (index == 7) dut.io.divrem_wb_uop(1).valid.poke(false.B)
      }

      // Write some values
      writePRF(10.U, 123.U, 0)
      writePRF(20.U, 456.U, 2)
      writePRF(30.U, 789.U, 4)

      // Read the written values through EXU ports
      dut.io.exu_issue_r_addr1(0).poke(10.U)
      dut.io.exu_issue_r_addr2(0).poke(20.U)
      dut.io.exu_issue_r_addr1(1).poke(30.U)
      dut.io.exu_issue_r_addr2(1).poke(10.U) // Read the same address again

      dut.clock.step()

      dut.io.exu_issue_r_value1(0).expect(123.U)
      dut.io.exu_issue_r_value2(0).expect(456.U)
      dut.io.exu_issue_r_value1(1).expect(789.U)
      dut.io.exu_issue_r_value2(1).expect(123.U)

      // Read through Store ports
      dut.io.st_issue_r_addr1.poke(20.U)
      dut.io.st_issue_r_addr2.poke(30.U)

      dut.clock.step()

      dut.io.st_issue_r_value1.expect(456.U)
      dut.io.st_issue_r_value2.expect(789.U)

      // Read through Load port
      dut.io.ld_issue_r_addr1.poke(10.U)

      dut.clock.step()

      dut.io.ld_issue_r_value1.expect(123.U)

      // Write a new value to the same address
      writePRF(10.U, 999.U, 1)

      // Read again to see the updated value
      dut.io.exu_issue_r_addr1(0).poke(10.U)
      dut.clock.step()
      dut.io.exu_issue_r_value1(0).expect(999.U)
    }
  }
}
