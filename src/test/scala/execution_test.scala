import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3.util._

import rsd_rv32.common._
import rsd_rv32.scheduler._
import rsd_rv32.execution.{ALU,ALUFU}

class execution_test {

}


class ALUTest extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()

  // ALU操作码定义(与ALUConsts保持一致)
  object ALUOp {
    val ADD    = 0.U(4.W)
    val SUB    = 0.U(4.W)
    val SLL    = 1.U(4.W)
    val SLT    = 2.U(4.W)
    val SLTU   = 3.U(4.W)
    val XOR    = 4.U(4.W)
    val SRL    = 5.U(4.W)
    val SRA    = 5.U(4.W)
    val OR     = 6.U(4.W)
    val AND    = 7.U(4.W)
  }

  "ALU" should "correctly perform arithmetic operations" in {
    test(new ALU) { dut =>
      // ADD测试
      dut.io.in1.poke(5.U)
      dut.io.in2.poke(3.U)
      dut.io.fn.poke(ALUOp.ADD)
      dut.io.out.expect(8.U)

      // SUB测试
      dut.io.fn.poke(ALUOp.SUB)
      dut.io.out.expect(2.U)

      // 溢出测试
      dut.io.in1.poke("hffffffff".U)
      dut.io.in2.poke(1.U)
      dut.io.fn.poke(ALUOp.ADD)
      dut.io.out.expect(0.U)
    }
  }

  "ALU" should "correctly perform logical operations" in {
    test(new ALU) { dut =>
      // AND测试
      dut.io.in1.poke("hff00ff00".U)

      dut.io.in2.poke("h0f0f0f0f".U)
      dut.io.fn.poke(ALUOp.AND)
      dut.io.out.expect("h0f000f00".U)

      // OR测试
      dut.io.fn.poke(ALUOp.OR)
      dut.io.out.expect("hff0fff0f".U)

      // XOR测试
      dut.io.fn.poke(ALUOp.XOR)
      dut.io.out.expect("hf00ff00f".U)
    }
  }

  "ALU" should "correctly perform shift operations" in {
    test(new ALU) { dut =>
      // SLL测试
      dut.io.in1.poke("h0000000f".U)
      dut.io.in2.poke(4.U)
      dut.io.fn.poke(ALUOp.SLL)
      dut.io.out.expect("h000000f0".U)

      // SRL测试
      dut.io.in1.poke("hf0000000".U)
      dut.io.in2.poke(4.U)
      dut.io.fn.poke(ALUOp.SRL)
      dut.io.out.expect("h0f000000".U)

      // SRA测试(符号位保持)
      dut.io.fn.poke(ALUOp.SRA)
      dut.io.out.expect("hff000000".U)
    }
  }

  "ALU" should "correctly perform comparison operations" in {
    test(new ALU {
      dut =>
      // SLT测试(有符号比较)
      dut.io.in1.poke("h80000000".U) // -2147483648
      dut.io.in2.poke(0.U)
      dut.io.fn.poke(ALUOp.SLT)
      dut.io.out.expect(1.U) // -2^31 < 0

      // SLTU测试(无符号比较)
      dut.io.fn.poke(ALUOp.SLTU)
      dut.io.out.expect(0.U) // 0x80000000 > 0 (无符号)
    })
  }

  "ALFU" should "correctly handle LUI instruction" in {
    test(new ALUFU {
      dut =>
      // 准备LUI指令(u型立即数)
      val luiInstr = "b00000000000000000000_10101_0110111".U // lui x10, 0x10101
      dut.io.uop.bits.instr.poke(luiInstr)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.IMM)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.Z)
      dut.io.uop.valid.poke(true.B)

      // 检查输出
      dut.clock.step()
      dut.out.bits.pdst_value.expect("h10101000".U)
    })
  }

  "ALFU" should "correctly handle AUIPC instruction" in {
    test(new ALUFU) { dut =>
      // 准备AUIPC指令(u型立即数)
      val auipcInstr = "b00000000000000000000_10101_0010111".U // auipc x10, 0x10101
      dut.io.uop.bits.instr.poke(auipcInstr)
      dut.io.uop.bits.instr_addr.poke("h1000_0000".U)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.PC)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.IMM)
      dut.io.uop.valid.poke(true.B)

      // 检查输出(PC + imm)
      dut.clock.step()
      dut.out.bits.pdst_value.expect("h1000_0000".U + "h10101000".U)
    }
  }

 /* "ALFU" should "correctly handle register operations" in {
    test(new ALUFU) { dut =>
      // 准备ADD指令
      dut.io.uop.bits.ps1_value.poke(5.U)
      dut.io.uop.bits.ps2_value.poke(3.U)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.alu_fn.poke(ALUOp.ADD)
      dut.io.uop.valid.poke(true.B)

      // 检查输出
      dut.clock.step()
      dut.out.bits.pdst_value.expect(8.U)
    }
  }

  "ALFU" should "handle back-to-back operations" in {
    test(new ALUFU) { dut =>
      // 第一个操作: ADD
      dut.io.uop.bits.ps1_value.poke(10.U)
      dut.io.uop.bits.ps2_value.poke(5.U)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.alu_fn.poke(ALUOp.ADD)
      dut.io.uop.valid.poke(true.B)
      dut.clock.step()
      dut.out.bits.pdst_value.expect(15.U)

      // 第二个操作: SUB
      dut.io.uop.bits.ps1_value.poke(10.U)
      dut.io.uop.bits.ps2_value.poke(5.U)
      dut.io.uop.bits.alu_fn.poke(ALUOp.SUB)
      dut.clock.step()
      dut.out.bits.pdst_value.expect(5.U)
    }
  }

  */
}
