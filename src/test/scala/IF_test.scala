import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import rsd_rv32.frontend._
import rsd_rv32.common._

class FetchUnitTest extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p: Parameters = new Parameters
    "FetchUnit"should "fetch two instructions and produce valid uops" in {
    test(new FetchUnit).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.rob_commitsignal(0).valid.poke(false.B)
      c.io.id_ready.poke(true.B)
      c.io.branch_pred.poke(false.B)
      c.io.btb_hit(0).poke(false.B)
      c.io.btb_hit(1).poke(false.B)
      c.io.target_PC.poke(0.U)
      c.io.GHR.poke(0.U)

      c.clock.step()

      c.io.instr(0).poke("b00000000000000000000000000010011".U) // addi x0, x0, 0
      c.io.instr(1).poke("b00000000000000000000000000010011".U)
      c.clock.step()

      c.io.id_uop(0).valid.expect(true.B, "uop 0 应该是true")
      c.io.id_uop(1).valid.expect(true.B, "uop 1 应该是true")
    }
  }

  "FetchUnit" should "suppress the second uop when 2 BTB hits" in {
    test(new FetchUnit).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
   
      c.io.rob_commitsignal(0).valid.poke(false.B)
      c.io.id_ready.poke(true.B)
      c.io.branch_pred.poke(true.B)
      c.io.btb_hit(0).poke(true.B)
      c.io.btb_hit(1).poke(true.B)
      c.io.target_PC.poke(0.U)
      c.io.GHR.poke(0.U)

      c.clock.step()


      c.io.instr(0).poke("b00000000000000000000000000010011".U)
      c.io.instr(1).poke("b00000000000000000000000000010011".U)
      c.clock.step()


      c.io.id_uop(0).valid.expect(true.B, "第一条应保留")
      c.io.id_uop(1).valid.expect(false.B, "第二条应舍去")
    }
  }
}
