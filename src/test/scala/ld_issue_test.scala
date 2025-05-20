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

class ld_issue_test extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()
  "ld_issue" should "accept the instr from dispatch unit and issue correctly" in {
    test(new ld_issue_queue()) { dut =>
      dut.io.ld_issue_uop.valid.poke(1.U)
      for(i <- 0 until p.STISSUE_DEPTH){
        dut.io.st_issue_unbusy(i).poke(1.U)
      }
      dut.io.load_uop.ready.poke(1.U)
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(0.U)
      dut.io.ld_issue_uop.bits(1).valid.poke(0.U)
      dut.clock.step()
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(2.U)
      dut.io.ld_issue_uop.bits(1).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(1).bits.iq_index.poke(1.U)
      dut.io.ld_issue_uop.bits(1).bits.ps1.poke(2.U)
      dut.clock.step(2)
      dut.io.wb_uop1(1).valid.poke(1.U)
      dut.io.wb_uop1(1).bits.pdst.poke(3.U)
      dut.io.wb_uop1(3).valid.poke(1.U)
      dut.io.wb_uop1(3).bits.pdst.poke(2.U)
      dut.clock.step(2)
      dut.io.load_uop.valid.expect(1.U)
      dut.clock.step()
      println("test pass")
    }
  }
  "ld_issue" should "accept 2 instrs from dispatch unit and issue correctly" in {
    test(new ld_issue_queue()) { dut =>
      dut.io.ld_issue_uop.valid.poke(1.U)
      //一次性接收两条条目
      println("the case that receive 2 content")
      dut.io.load_uop.ready.poke(1.U)
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(1).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(5.U)
      dut.io.ld_issue_uop.bits(1).bits.iq_index.poke(6.U)
      dut.io.ld_issue_uop.bits(0).bits.ps1.poke(2.U)
      dut.clock.step()
      dut.io.ld_issue_uop.bits(0).valid.poke(0.U)
      dut.io.ld_issue_uop.bits(1).valid.poke(0.U)
      dut.clock.step()
      dut.clock.step()
      dut.io.prf_valid(2).poke(1)
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(4.U)
      dut.clock.step(3)
      println("test pass")
    }
  }
  //检查发生误预测时是否能够flush
  "ld_issue" should "flush correctly when mispred" in {
    test(new ld_issue_queue()) { dut =>
      dut.io.ld_issue_uop.valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(3.U)
      dut.io.ld_issue_uop.bits(1).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(1).bits.iq_index.poke(4.U)
      dut.clock.step()
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(2.U)
      dut.io.ld_issue_uop.bits(1).bits.iq_index.poke(1.U)
      dut.clock.step()
      dut.io.ld_issue_uop.bits(0).valid.poke(0.U)
      dut.io.ld_issue_uop.bits(1).valid.poke(0.U)
      dut.io.rob_commitsignal(0).valid.poke(1.U)
      dut.io.rob_commitsignal(0).bits.mispred.poke(1.U)
      dut.clock.step(1)
      for (i <- 0 until 5){
        dut.io.queue(i).busy.expect(0.U)
      }
      dut.clock.step(1)
      println("test pass")
    }
  }
  //是否可正常维护就绪矩阵
  "ld_issue" should "communicate with st_issue correctly" in {
    test(new ld_issue_queue()) { dut =>
      dut.io.ld_issue_uop.valid.poke(1.U)
      for(i <- 0 until p.STISSUE_DEPTH){
        dut.io.st_issue_unbusy(i).poke(1.U)
      }
      dut.clock.step()
      println("初始状态，st_issue全空")
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(1.U)
      dut.clock.step()
      println("ld_issue入队")
      dut.io.ld_issue_uop.bits(0).valid.poke(0.U)
      dut.io.st_issue_unbusy(2).poke(0.U)
      dut.clock.step()
      println("st_issue入队，busy条件改变")
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(2.U)
      dut.clock.step()
      println("ld_issue入队")
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(3.U)
      dut.io.st_issued_index.valid.poke(1.U)
      dut.io.st_issued_index.bits.poke(2.U)
      dut.clock.step()
      println("st_issue出队，更新st_ready，同时ld_issue入队，此时所有命令都应就绪")
      dut.clock.step()
    }
  }
  //在ld unready时级间流水寄存器不能被冲刷
  "ld_issue" should "correctly" in {
    test(new ld_issue_queue()) { dut =>
      dut.io.ld_issue_uop.valid.poke(1.U)
      for(i <- 0 until p.STISSUE_DEPTH){
        dut.io.st_issue_unbusy(i).poke(1.U)
      }
      dut.io.load_uop.ready.poke(1.U)
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(0.U)
      dut.io.ld_issue_uop.bits(1).valid.poke(0.U)
      dut.clock.step()
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(0).bits.iq_index.poke(2.U)
      dut.io.ld_issue_uop.bits(1).valid.poke(1.U)
      dut.io.ld_issue_uop.bits(1).bits.iq_index.poke(1.U)
      dut.io.ld_issue_uop.bits(1).bits.ps1.poke(2.U)
      dut.io.ld_issue_uop.bits(1).bits.pdst.poke(5.U)
      dut.clock.step()
      dut.io.ld_issue_uop.bits(0).valid.poke(0.U)
      dut.io.ld_issue_uop.bits(1).valid.poke(0.U)
      dut.clock.step()
      dut.io.wb_uop1(1).valid.poke(1.U)
      dut.io.wb_uop1(1).bits.pdst.poke(3.U)
      dut.io.wb_uop1(3).valid.poke(1.U)
      dut.io.wb_uop1(3).bits.pdst.poke(2.U)
      dut.clock.step()
      dut.clock.step()
      dut.io.load_uop.ready.poke(0.U)
      dut.clock.step(2)
      dut.io.load_uop.ready.poke(1.U)
      dut.io.load_uop.valid.expect(1.U)
      dut.io.load_uop.bits.pdst.expect(5.U)
      dut.clock.step()
      dut.io.load_uop.bits.pdst.expect(0.U)
      dut.io.load_uop.valid.expect(0.U)
    }
  }
  "ld_issue" should "deal with store and load correctly" in {
    test(new ld_issue_queue()) { dut =>
      dut.io.ld_issue_uop.valid.poke(1.U)
      for (i <- 0 until p.STISSUE_DEPTH) {
        dut.io.st_issue_unbusy(i).poke(1.U)
      }
      dut.io.st_issue_uop.valid.poke(1.U)
      dut.io.st_issue_uop.bits(0).valid.poke(1.U)
      dut.io.st_issue_uop.bits(0).bits.iq_index.poke(1.U)
      dut.io.rob_uop(0).valid.poke(1.U)
      dut.io.rob_uop(0).bits.instr_type.poke(InstrType.ST)
      dut.io.ld_issue_uop.bits(0).valid.poke(1.U)
      dut.clock.step()
      dut.io.ld_issue_uop.bits(0).valid.poke(0.U)
      dut.io.st_issue_uop.bits(0).valid.poke(0.U)
      dut.io.rob_uop(0).valid.poke(0.U)
      dut.clock.step(2)
    }
  }
}

