package rsd_rv32

import chisel3._
import chiseltest.WriteVcdAnnotation

import scala.util.Random
import chisel3.stage.ChiselGeneratorAnnotation
import chisel3.util._
import chiseltest._
import firrtl.options.TargetDirAnnotation
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.flatspec.AnyFlatSpec
import rsd_rv32.scheduler._
import rsd_rv32.common.Parameters
import rsd_rv32.common._
import chiseltest.testableClock
import rsd_rv32.execution.{LSU, LoadPipeline, StoreQueue}
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
  "Loadpipeline" should "correctly handle signed and unsigned loads" in {
    test(new LoadPipeline()) { c =>
//      test for STQ
//      c.io.st_cnt.poke(2.U)
//      c.clock.step(1)
//
//      c.io.st_cnt.poke(0.U)
//      c.io.dataAddr_into_stq.valid.poke(true.B)
//      c.io.dataAddr_into_stq.bits.poke("h1000".U)
//      c.io.data_into_stq.poke(16.U)
//      c.io.stq_index.poke(0.U)
//      c.io.st_func3.poke(1.U)
//      c.clock.step(1)
//
//      c.io.st_cnt.poke(1.U)
//      c.io.dataAddr_into_stq.valid.poke(true.B)
//      c.io.dataAddr_into_stq.bits.poke("h1002".U)
//      c.io.data_into_stq.poke(32.U)
//      c.io.stq_index.poke(1.U)
//      c.io.st_func3.poke(2.U)
//      c.clock.step(1)
//
//      c.io.st_cnt.poke(1.U)
//      c.io.dataAddr_into_stq.valid.poke(true.B)
//      c.io.dataAddr_into_stq.bits.poke("h100C".U)
//      c.io.data_into_stq.poke(32.U)
//      c.io.stq_index.poke(2.U)
//      c.io.st_func3.poke(2.U)
//      c.clock.step(1)
//
//
//      c.io.st_cnt.poke(0.U)
//      c.io.dataAddr_into_stq.valid.poke(true.B)
//      c.io.dataAddr_into_stq.bits.poke("h100D".U)
//      c.io.data_into_stq.poke(8.U)
//      c.io.stq_index.poke(3.U)
//      c.io.st_func3.poke(0.U)
//      c.clock.step(1)
//
//      c.io.dataAddr_into_stq.valid.poke(false.B)
//      c.io.rob_commitsignal(0).valid.poke(true.B)
//      c.io.rob_commitsignal(0).bits.mispred.poke(false)
//      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Store)
//      c.io.rob_commitsignal(1).valid.poke(false.B)
//      c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Arithmetic)
//      c.clock.step(1)
//
//      c.io.rob_commitsignal(0).valid.poke(false)
//      c.io.rob_commitsignal(0).bits.mispred.poke(false)
//      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Arithmetic)
//      c.io.rob_commitsignal(1).valid.poke(false.B)
//      c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Arithmetic)
//      c.io.stqReq.ready.poke(true.B)
//      c.clock.step(1)
//
//      c.io.rob_commitsignal(0).bits.mispred.poke(true.B)
//      c.io.rob_commitsignal(0).valid.poke(true.B)
//      c.clock.step(1)
//
//      c.io.addr_search_stq.valid.poke(true.B)
//      c.io.addr_search_stq.bits.poke("h100D".U)
//      c.io.ld_func3.poke(0.U)
//      c.io.input_tail.poke(3.U)
//      c.io.rob_commitsignal(0).valid.poke(true.B)
//      c.io.rob_commitsignal(0).bits.mispred.poke(false)
//      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Store)
//      c.io.rob_commitsignal(1).valid.poke(true.B)
//      c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Store)
//      c.clock.step(1)
//
//      c.io.rob_commitsignal(0).valid.poke(false.B)
//      c.io.rob_commitsignal(0).bits.mispred.poke(false)
//      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Store)
//      c.io.rob_commitsignal(1).valid.poke(false.B)
//      c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Arithmetic)
//      c.io.stqReq.ready.poke(true.B)
//      c.io.addr_search_stq.valid.poke(false.B)
//      c.clock.step(1)
//
//      c.io.ld_func3.poke(1.U)
//      c.io.input_tail.poke(4.U)
//      c.io.stqReq.ready.poke(false.B)
//      c.io.addr_search_stq.valid.poke(true.B)
//      c.io.addr_search_stq.bits.poke("h100D".U)
//      c.io.rob_commitsignal(0).valid.poke(true.B)
//      c.io.rob_commitsignal(0).bits.mispred.poke(false)
//      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Store)
//      c.io.rob_commitsignal(1).valid.poke(false.B)
//      c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Arithmetic)
//      c.clock.step(1)
//
//      c.io.stqReq.ready.poke(true.B)
//      c.io.input_tail.poke(4.U)
//      c.io.addr_search_stq.valid.poke(true.B)
//      c.io.addr_search_stq.bits.poke("h100C".U)
//      c.io.ld_func3.poke(1.U)
//      c.io.input_tail.poke(4.U)
//      c.io.rob_commitsignal(0).valid.poke(true.B)
//      c.io.rob_commitsignal(0).bits.mispred.poke(false)
//      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Store)
//      c.io.rob_commitsignal(1).valid.poke(false.B)
//      c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Arithmetic)
//      c.clock.step(1)
//
//      c.io.rob_commitsignal(0).valid.poke(false.B)
//      c.io.rob_commitsignal(0).bits.mispred.poke(false)
//      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Store)
//      c.io.rob_commitsignal(1).valid.poke(false.B)
//      c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Arithmetic)
//      c.io.addr_search_stq.valid.poke(false.B)
//      c.io.stqReq.ready.poke(false.B)
//      c.clock.step(1)
//
//
//      c.io.stqReq.ready.poke(true.B)
//      c.clock.step(1)
//
//      c.io.stqReq.ready.poke(false.B)
//      c.clock.step(1)
//
//      c.io.stqReq.ready.poke(true.B)
//      c.clock.step(1)
//
//      c.io.stqReq.ready.poke(false.B)
//      c.clock.step(1)
      def makeinstr(imm : Int,func3 : Int): UInt = {
        val rs1    = BigInt("001", 16)   // 5 bits
        val rd     = BigInt("002", 16)   // 5 bits
        val instrBigInt = (BigInt(imm) << (5 + 3 + 5)) | (rs1 << (3 + 5)) | (BigInt(func3) << 5) | rd

        instrBigInt.U(25.W) // 总宽度 = 12+5+3+5 = 25 bits
    }



      c.io.load_uop.bits.instr.poke(makeinstr(4,1))
      c.io.load_uop.bits.pdst.poke(1.U)
      c.io.load_uop.bits.ps1_value.poke("h1008".U)
      c.io.load_uop.bits.rob_index.poke(2.U)
      c.io.load_uop.bits.stq_tail.poke(4.U)
      c.io.load_uop.valid.poke(true.B)
      c.clock.step(1)

      c.io.load_uop.bits.instr.poke(makeinstr(1,5))
      c.io.load_uop.bits.pdst.poke(3.U)
      c.io.load_uop.bits.ps1_value.poke("h1001".U)
      c.io.load_uop.bits.rob_index.poke(3.U)
      c.io.load_uop.bits.stq_tail.poke(4.U)
      c.io.data_out_stq.data.poke("h0820".U)
      c.io.data_out_stq.bit_valid.poke("hFFFF".U)
      c.clock.step(1)

      c.io.data_out_stq.data.poke("h0020".U)
      c.io.data_out_stq.bit_valid.poke("h00FF".U)
      c.io.load_uop.valid.poke(false.B)
      c.io.ldReq.ready.poke(false.B)
      c.clock.step(1)

      c.io.ldReq.ready.poke(true.B)
      c.io.data_out_mem.poke("h0700".U)
      c.clock.step(1)

      c.io.ldReq.ready.poke(false.B)
      c.clock.step(2)

      c.io.rob_commitsignal(0).valid.poke(true.B)
      c.io.rob_commitsignal(0).bits.mispred.poke(true.B)
      c.clock.step(2)


    }
  }

}

