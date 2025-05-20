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

// <<<<<<< HEAD
// ||||||| 37cfc08
//       c.clock.step()

// =======

// >>>>>>> fadc7f7dd5ef8ab35d2e6aa1cce92e256ea93f31
      c.io.instr(0).poke("b00000000000000000000000000010011".U) // addi x0, x0, 0
      c.io.instr(1).poke("b00000000000000000000000000010011".U)


      c.clock.step()
      c.clock.step()

      c.io.id_uop(0).valid.expect(true.B, "uop 0 应该是true")
      c.io.id_uop(1).valid.expect(true.B, "uop 1 应该是true")
    }
  }

// <<<<<<< HEAD
//   "FetchUnit" should "suppress the second uop when 2 BTB hits" in {
//     test(new FetchUnit).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
   
// ||||||| 37cfc08
//   "FetchUnit" should "suppress the second uop when 2 BTB hits" in {
//     test(new FetchUnit()(new DefaultParameters)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
   
// =======
  "FetchUnit" should "select correct next PC based on ROB > BP > default" in {
    test(new FetchUnit()(p)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
// >>>>>>> fadc7f7dd5ef8ab35d2e6aa1cce92e256ea93f31
      c.io.rob_commitsignal(0).valid.poke(false.B)
      c.io.rob_commitsignal(0).bits.mispred.poke(false.B)
      c.io.rob_commitsignal(0).bits.instr_addr.poke("h1000".U)
      c.io.id_ready.poke(true.B)
      c.io.branch_pred.poke(true.B)
      c.io.btb_hit(0).poke(false.B)
      c.io.btb_hit(1).poke(false.B)
      c.io.target_PC.poke("h3000".U)
      c.io.GHR.poke(0.U)

// <<<<<<< HEAD
//       c.io.instr(0).poke("b00000000000000000000000000010011".U)
//       c.io.instr(1).poke("b00000000000000000000000000010011".U)



//       c.clock.step()

// ||||||| 37cfc08
//       c.clock.step()

// =======
// >>>>>>> fadc7f7dd5ef8ab35d2e6aa1cce92e256ea93f31

// <<<<<<< HEAD
// ||||||| 37cfc08
//       c.io.instr(0).poke("b00000000000000000000000000010011".U)
//       c.io.instr(1).poke("b00000000000000000000000000010011".U)
// =======
      c.io.instr(0).poke("b00000000000000000000000000010011".U)
      c.io.instr(1).poke("b00000000000000000000000000010011".U)


// >>>>>>> fadc7f7dd5ef8ab35d2e6aa1cce92e256ea93f31
      c.clock.step()


      c.clock.step()
      c.io.instr_addr.expect("h3000".U)


    }
  }


  "FetchUnit" should "suppress the second uop when young instr hits" in {
    test(new FetchUnit()(p)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      c.io.rob_commitsignal(0).valid.poke(false.B)
      c.io.id_ready.poke(true.B)
      c.io.branch_pred.poke(true.B)
      c.io.btb_hit(0).poke(false.B)
      c.io.btb_hit(1).poke(true.B)
      c.io.target_PC.poke(0.U)
      c.io.GHR.poke(0.U)

      c.io.instr(0).poke("b00000000000000000000000000010011".U)
      c.io.instr(1).poke("b00000000000000000000000000010011".U)



      c.clock.step()


      c.clock.step()


      c.io.id_uop(0).valid.expect(true.B, "第一条应更改")
      c.io.id_uop(1).valid.expect(false.B, "第二条应舍去")
    }
  }
}
