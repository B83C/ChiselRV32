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
import rsd_rv32.execution.{LSU, LSUArbiter, LoadPipeline, StorePipeline, StoreQueue}
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
  "LSUArbiter" should "correctly forward store and load request with RR priority" in {
    test(new LSUArbiter()){ c =>



      c.io.stqReq.valid.poke(true.B)
      c.io.stqReq.bits.write_en.poke(true.B)
      c.io.stqReq.bits.func3.poke(1.U)
      c.io.stqReq.bits.data.poke(16.U)
      c.io.stqReq.bits.data_Addr.poke("h1000".U)
      c.io.ldReq.valid.poke(true.B)
      c.io.ldReq.bits.data.poke(0.U)
      c.io.ldReq.bits.write_en.poke(false.B)
      c.io.ldReq.bits.func3.poke(1.U)
      c.io.ldReq.bits.data_Addr.poke("h100D".U)
      c.clock.step(1)

      c.io.stqReq.valid.poke(true.B)
      c.io.stqReq.bits.write_en.poke(true.B)
      c.io.stqReq.bits.func3.poke(1.U)
      c.io.stqReq.bits.data.poke(32.U)
      c.io.stqReq.bits.data_Addr.poke("h1002".U)
      c.io.ldReq.valid.poke(true.B)
      c.io.ldReq.bits.data.poke(0.U)
      c.io.ldReq.bits.write_en.poke(false.B)
      c.io.ldReq.bits.func3.poke(1.U)
      c.io.ldReq.bits.data_Addr.poke("h100D".U)
      c.clock.step(2)

      c.io.ldReq.valid.poke(false.B)
      c.clock.step(2)
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
      //
      //
      //
      //      c.io.load_uop.bits.instr.poke(makeinstr(4,1))
      //      c.io.load_uop.bits.pdst.poke(1.U)
      //      c.io.load_uop.bits.ps1_value.poke("h1008".U)
      //      c.io.load_uop.bits.rob_index.poke(2.U)
      //      c.io.load_uop.bits.stq_tail.poke(4.U)
      //      c.io.load_uop.valid.poke(true.B)
      //      c.clock.step(1)
      //
      //      c.io.load_uop.bits.instr.poke(makeinstr(1,5))
      //      c.io.load_uop.bits.pdst.poke(3.U)
      //      c.io.load_uop.bits.ps1_value.poke("h1001".U)
      //      c.io.load_uop.bits.rob_index.poke(3.U)
      //      c.io.load_uop.bits.stq_tail.poke(4.U)
      //      c.io.data_out_stq.data.poke("h0820".U)
      //      c.io.data_out_stq.bit_valid.poke("hFFFF".U)
      //      c.clock.step(1)
      //
      //      c.io.data_out_stq.data.poke("h0020".U)
      //      c.io.data_out_stq.bit_valid.poke("h00FF".U)
      //      c.io.load_uop.valid.poke(false.B)
      //      c.io.ldReq.ready.poke(false.B)
      //      c.clock.step(1)
      //
      //      c.io.ldReq.ready.poke(true.B)
      //      c.io.data_out_mem.poke("h0700".U)
      //      c.clock.step(1)
      //
      //      c.io.ldReq.ready.poke(false.B)
      //      c.clock.step(2)
      //
      //      c.io.rob_commitsignal(0).valid.poke(true.B)
      //      c.io.rob_commitsignal(0).bits.mispred.poke(true.B)
      //      c.clock.step(2)



      //      def makeinstr(imm : Int,func3 : Int): UInt = {
      //        val rs1    = BigInt("001", 16)   // 5 bits
      //        val rs2     = BigInt("002", 16)   // 5 bits
      //        val immhigh = (imm >> 5) & 0x7f   // 7 bits
      //        val immlow = imm & 0x1F           // 5 bits
      //        val instrBigInt = (BigInt(immhigh) << 18) | rs2 << 13 |(rs1 << (3 + 5)) | (BigInt(func3) << 5) | BigInt(immlow)
      //
      //        instrBigInt.U(25.W) // 总宽度 = 5 + 3 + 5 + 5 + 7 = 25 bits
      //      }
      //
      //      c.io.store_uop.valid.poke(true.B)
      //      c.io.store_uop.bits.instr.poke(makeinstr(0,1))
      //      c.io.store_uop.bits.ps1_value.poke("h1000".U)
      //      c.io.store_uop.bits.ps2_value.poke(16.U)
      //      c.io.store_uop.bits.rob_index.poke(0.U)
      //      c.io.store_uop.bits.stq_index.poke(0.U)
      //      c.clock.step(1)
      //
      //      c.io.store_uop.bits.instr.poke(makeinstr(2,1))
      //      c.io.store_uop.bits.ps1_value.poke("h1000".U)
      //      c.io.store_uop.bits.ps2_value.poke(32.U)
      //      c.io.store_uop.bits.rob_index.poke(1.U)
      //      c.io.store_uop.bits.stq_index.poke(1.U)
      //      c.clock.step(1)
      //
      //      c.io.store_uop.valid.poke(false.B)
      //      c.clock.step(3)
      //
      //      c.io.rob_commitsignal(0).valid.poke(true.B)
      //      c.io.rob_commitsignal(0).bits.mispred.poke(true.B)
      //      c.clock.step(2)
      //      test for StorePipeline

    }
  }

}

