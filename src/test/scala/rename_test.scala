import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3.util._

import rsd_rv32.common._
import rsd_rv32.scheduler._

/** 测试框架 */
object Constants {
  val and1 = 0b0000000_00001_00001_111_00001
  val and2 = 0b0000000_00001_00010_111_00001
  val sb = 0b0000000_00001_00010_000_00000
  val beq = 0b0000000_00001_00010_000_00000
}
class MyModuleTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {
    val defaultParams = Parameters()
    implicit val p: Parameters = defaultParams.copy(PRF_DEPTH = 35)

    "Rename_Unit" should "correctly work" in {
        test(new RenameUnit()) { c =>
            //测试两条指令的rd相同，较老的指令的rs2和较年轻指令的rd相同
            c.io.id_uop(0).valid.poke(true.B)
            c.io.id_uop(1).valid.poke(true.B)
            c.io.id_uop(0).bits.instr_type.poke(InstrType.ALU)
            c.io.id_uop(1).bits.instr_type.poke(InstrType.ALU)
            c.io.id_uop(0).bits.instr.poke(Constants.and1.U)
            c.io.id_uop(1).bits.instr.poke(Constants.and2.U)

            c.io.dis_ready.poke(true.B)

            c.io.rob_commitsignal(0).valid.poke(false.B)
            c.io.rob_commitsignal(1).valid.poke(false.B)
            
            println("cycle 1")
            c.clock.step()

            c.io.id_uop(1).bits.instr_type.poke(InstrType.ST)
            c.io.id_uop(1).bits.instr.poke(Constants.sb.U)

            println("cycle 2")
            c.clock.step()

            c.io.id_uop(0).bits.instr_type.poke(InstrType.Branch)
            c.io.id_uop(0).bits.instr.poke(Constants.beq.U)

            c.io.id_uop(1).valid.poke(false.B)

            println("cycle 3")
            c.clock.step()

            c.io.rob_commitsignal(0).valid.poke(true.B)
            c.io.rob_commitsignal(0).bits.mispred.poke(false.B)
            c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Arithmetic)
            c.io.rob_commitsignal(0).bits.payload.poke(0b100000_00001.U)

            c.io.rob_commitsignal(1).valid.poke(true.B)
            c.io.rob_commitsignal(1).bits.mispred.poke(false.B)
            c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Arithmetic)
            c.io.rob_commitsignal(1).bits.payload.poke(0b100001_00001.U)

            c.io.id_uop(1).valid.poke(true.B)
            c.io.id_uop(1).bits.instr_type.poke(InstrType.ALU)
            c.io.id_uop(1).bits.instr.poke(Constants.and1.U)

            println("cycle 4")
            c.clock.step()

            c.io.rob_commitsignal(0).bits.payload.poke(0b100010_00001.U)
            c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Store)
            c.io.rob_commitsignal(1).bits.payload.poke(0.U)

            c.io.dis_ready.poke(false.B)
            println("cycle 5")
            c.clock.step()

            c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Branch)
            c.io.rob_commitsignal(0).bits.payload.poke(0.U)

            c.io.rob_commitsignal(1).valid.poke(false.B)
            
            c.io.dis_ready.poke(true.B)
            println("cycle 6")
            c.clock.step()

            c.io.rob_commitsignal(0).bits.mispred.poke(true.B)

            c.io.id_uop(0).valid.poke(false.B)
            c.io.id_uop(1).valid.poke(false.B)

            println("cycle 7")
            c.clock.step()

            println("cycle 8")
            c.clock.step()

            /*c.io.rob_commitsignal(0).bits.mispred.poke(true.B)
            c.io.rob_commitsignal(0).valid.poke(true.B)*/


            
            
        }
    }

  /*it should "increment 42 to 43" in {
    test(new MyModule) { c =>
      c.io.in.poke(42.U)
      c.clock.step()
      c.io.out.expect(43.U)
    }
  }*/
}
