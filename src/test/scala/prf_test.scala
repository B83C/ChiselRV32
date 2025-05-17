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
          dut.io.bu_wb_uop(1).valid.poke(true.B)
          dut.io.bu_wb_uop(1).bits.pdst.poke(addr)
          dut.io.bu_wb_uop(1).bits.pdst_value.poke(data)
        } else if (index == 4) {
          dut.io.mul_wb_uop(0).valid.poke(true.B)
          dut.io.mul_wb_uop(0).bits.pdst.poke(addr)
          dut.io.mul_wb_uop(0).bits.pdst_value.poke(data)
        } else if (index == 5) {
          dut.io.mul_wb_uop(1).valid.poke(true.B)
          dut.io.mul_wb_uop(1).bits.pdst.poke(addr)
          dut.io.mul_wb_uop(1).bits.pdst_value.poke(data)
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

class PRF_Valid_Test extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = new Parameters

  it should "test basic valid bit updates in PRF_Valid" in {
    test(new PRF_Valid()) { dut =>
      // Initially all should be invalid (assuming default reset state)
      dut.io.prf_valid.peek().foreach(valid => valid.expect(false.B))

      // Simulate allocation of physical registers
      dut.io.amt(5).poke(10.U) // Rename logical reg 5 to physical reg 10
      dut.io.amt(10).poke(20.U) // Rename logical reg 10 to physical reg 20
      dut.clock.step()

      // After allocation, the corresponding valid bits should still be false
      dut.io.prf_valid(10).peek().expect(false.B)
      dut.io.prf_valid(20).peek().expect(false.B)

      // Simulate commit of a result to physical register 10
      dut.io.rob_commitsignal(0).valid.poke(true.B)
      dut.io.rob_commitsignal(0).bits.pdest.poke(10.U)
      dut.clock.step()
      dut.io.rob_commitsignal(0).valid.poke(false.B)

      // Now physical register 10 should be valid
      dut.io.prf_valid(10).peek().expect(true.B)
      dut.io.prf_valid(20).peek().expect(false.B) // 20 should still be invalid

      // Simulate commit to physical register 20
      dut.io.rob_commitsignal(1).valid.poke(true.B)
      dut.io.rob_commitsignal(1).bits.pdest.poke(20.U)
      dut.clock.step()
      dut.io.rob_commitsignal(1).valid.poke(false.B)

      // Now both should be valid
      dut.io.prf_valid(10).peek().expect(true.B)
      dut.io.prf_valid(20).peek().expect(true.B)

      // Simulate another commit to the same register 10
      dut.io.rob_commitsignal(0).valid.poke(true.B)
      dut.io.rob_commitsignal(0).bits.pdest.poke(10.U)
      dut.clock.step()
      dut.io.rob_commitsignal(0).valid.poke(false.B)

      // It should still be valid
      dut.io.prf_valid(10).peek().expect(true.B)

    }
  }
}