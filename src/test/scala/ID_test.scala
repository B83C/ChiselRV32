import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import rsd_rv32.common._
import rsd_rv32.frontend.decode

class DecodeUnitTest extends AnyFlatSpec with ChiselScalatestTester {
  
  "Decoder" should "correctly decode instructions" in {
    test(new DecodeUnit).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      val instr = "h00410113".U                 // addi x2, x2, 4
      val addr = 0x1000.U
      implicit val p = Parameters()

      c.io.rename_ready.poke(true.B)
      for (i <- 0 until p.CORE_WIDTH) {
        c.io.id_uop(i).valid.poke(true.B)
        c.io.id_uop(i).bits.instr.poke(instr)
        c.io.id_uop(i).bits.instr_addr.poke(addr)
        c.io.id_uop(i).bits.target_PC.poke(0.U)
        c.io.id_uop(i).bits.GHR.poke(0.U)
        c.io.id_uop(i).bits.branch_pred.poke(false.B)
        c.io.id_uop(i).bits.btb_hit.poke(false.B)
      }

      // 先不考虑flush
      for (i <- 0 until p.CORE_WIDTH) {
        c.io.rob_commitsignal(i).valid.poke(false.B)
        c.io.rob_commitsignal(i).bits.mispred.poke(false.B)
      }

      c.clock.step()

      for (i <- 0 until p.CORE_WIDTH) {
        c.io.rename_uop(i).valid.expect(true.B)
        c.io.rename_uop(i).bits.instr.expect(instr(31, 7))
        c.io.rename_uop(i).bits.instr_type.expect(InstrType.ALU.U)
        c.io.rename_uop(i).bits.fu_signals.opr1_sel.expect(OprSel.REG.U)
        c.io.rename_uop(i).bits.fu_signals.opr2_sel.expect(OprSel.IMM.U)
      }
    }
  }




  //测试flush
  "Decoder" should "flush when needed" in {
    test(new DecodeUnit).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      val instr = "h00410113".U           // addi x2, x2, 4
      implicit val p = Parameters()

      c.io.rename_ready.poke(true.B)

      for (i <- 0 until p.CORE_WIDTH) {
        c.io.id_uop(i).valid.poke(true.B)
        c.io.id_uop(i).bits.instr.poke(instr)
        c.io.id_uop(i).bits.instr_addr.poke(0x1000.U)
        c.io.id_uop(i).bits.target_PC.poke(0.U)
        c.io.id_uop(i).bits.GHR.poke(0.U)
        c.io.id_uop(i).bits.branch_pred.poke(false.B)
        c.io.id_uop(i).bits.btb_hit.poke(false.B)
      }

      // 分支预测错误
      c.io.rob_commitsignal(0).valid.poke(true.B)
      c.io.rob_commitsignal(0).bits.mispred.poke(true.B)

      c.clock.step()

      for (i <- 0 until p.CORE_WIDTH) {
        c.io.rename_uop(i).valid.expect(false.B)
      }
    }
  }
}
