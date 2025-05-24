import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.scheduler._
import rsd_rv32.execution._


import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import rsd_rv32.common._
import rsd_rv32.common.Parameters


//ALU的测试，都跑通了
class ALUTest extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()

  // ALU操作码定义(与ALUConsts保持一致)
  object ALUOp {
    val ADD    = 0.U(4.W)
    val SUB    = 1.U(4.W)
    val SLL    = 2.U(4.W)
    val SLT    = 3.U(4.W)
    val SLTU   = 4.U(4.W)
    val XOR    = 5.U(4.W)
    val SRL    = 6.U(4.W)
    val SRA    = 7.U(4.W)
    val OR     = 8.U(4.W)
    val AND    = 9.U(4.W)
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

  "ALUFU" should "correctly handle LUI instruction" in {
    test(new ALUFU) {
      dut =>
      // 准备LUI指令(u型立即数)
      val luiInstr = "b0000000000001010100000000".U(25.W)
      dut.io.uop.bits.instr_.poke(luiInstr)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.IMM)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.Z)
      dut.io.uop.valid.poke(true.B)

      // 检查输出
      dut.clock.step()
      dut.out.bits.pdst_value.expect("h000A8000".U)
    }
  }


  "ALFU" should "correctly handle AUIPC instruction" in {
    test(new ALUFU) { dut =>
      val auipcInstr = "b0000000000010101000000000".U(25.W) // imm=0x10101 (20位)
      dut.io.uop.bits.instr_.poke(auipcInstr)
      dut.io.uop.bits.instr_addr.poke("h10000000".U)

      // 设置操作数选择
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.PC)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.IMM)
      dut.io.uop.valid.poke(true.B)

      // 计算预期结果（Scala侧）
      val immBits = auipcInstr(24,5)  // 提取高20位立即数
      val immValue = (immBits.litValue.toLong << 12)  // U型立即数左移12位
      val pcValue = 0x10000000L
      val expected = (pcValue + immValue).U  // 转换为Chisel的UInt

      // 检查输出(PC + imm)
      dut.clock.step()
      dut.out.bits.pdst_value.expect(expected)
    }
  }


"ALFU" should "correctly handle register operations" in {
    test(new ALUFU) { dut =>
      // 准备ADD指令
      val addInstr = "b0000000000100000100000011".U(25.W)
      dut.io.uop.bits.instr_.poke(addInstr)
      dut.io.uop.bits.ps1_value.poke(5.U)
      dut.io.uop.bits.ps2_value.poke(3.U)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.valid.poke(true.B)

      // 检查输出
      dut.clock.step()
      dut.out.bits.pdst_value.expect(8.U)
    }
  }

"ALFU" should "handle back-to-back operations" in {
    test(new ALUFU) { dut =>
      // 第一个操作: ADD
      val addInstr = "b0000000000100000100000011".U(25.W)
      dut.io.uop.bits.instr_.poke(addInstr)
      dut.io.uop.bits.ps1_value.poke(10.U)
      dut.io.uop.bits.ps2_value.poke(5.U)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.valid.poke(true.B)
      dut.clock.step()
      dut.out.bits.pdst_value.expect(15.U)

      // 第二个操作: SUB
      val subInstr = "b0100000000100000100000100".U(25.W)
      dut.io.uop.bits.instr_.poke(subInstr)
      dut.io.uop.bits.ps1_value.poke(10.U)
      dut.io.uop.bits.ps2_value.poke(5.U)
      dut.clock.step()
      dut.out.bits.pdst_value.expect(5.U)
    }


  }

}
//MUL的测试，都跑通了
class MULFUTest extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()

  object MULOp {
    val MUL = 0.U(3.W)
    val MULH = 1.U(3.W)
    val MULHSU = 2.U(3.W)
    val MULHU = 3.U(3.W)
  }


  "MULFU" should "correctly handle MUL operations" in {
    test(new MULFU) { dut =>
      // 设置25位指令字段：func3=000(MUL), rs2=2, rs1=1, rd=0
      dut.io.uop.bits.instr_.poke("b0000001_00010_00001_000_00000".U)
      dut.io.uop.bits.ps1_value.poke(5.U) // 操作数1 = 5
      dut.io.uop.bits.ps2_value.poke(3.U) // 操作数2 = 3
      dut.io.uop.bits.instr_type.poke(InstrType.MUL)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)

      dut.io.uop.valid.poke(true.B)


      dut.clock.step(16)

      dut.out.bits.pdst_value.expect(15.U) // 5 * 3 = 15
    }
  }

  "MULFU" should "正确执行MULH操作" in {
    test(new MULFU) { dut =>
      // func3=001(MULH)
      dut.io.uop.bits.instr_.poke("b0000001_00010_00001_001_00000".U)
      dut.io.uop.bits.ps1_value.poke("h80000000".U) // -2^31
      dut.io.uop.bits.ps2_value.poke("h80000000".U) // -2^31
      dut.io.uop.bits.instr_type.poke(InstrType.MUL)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.valid.poke(true.B)


      dut.clock.step(32)

      dut.out.bits.pdst_value.expect("h40000000".U) // 结果高32位
    }
  }

  "MULFU" should "正确处理背靠背乘法" in {
    test(new MULFU) { dut =>
      // 第一个乘法
      dut.io.uop.bits.instr_.poke("b0000001_00010_00001_000_00000".U)
      dut.io.uop.bits.ps1_value.poke(5.U)
      dut.io.uop.bits.ps2_value.poke(3.U)
      dut.io.uop.bits.instr_type.poke(InstrType.MUL)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.valid.poke(true.B)


      dut.clock.step(32)

      dut.out.bits.pdst_value.expect(15.U)

      // 立即开始第二个乘法
      dut.io.uop.bits.instr_.poke("b0000001_00011_00010_000_00001".U)
      dut.io.uop.bits.ps1_value.poke(7.U)
      dut.io.uop.bits.ps2_value.poke(6.U)


      dut.clock.step(32)

      dut.out.bits.pdst_value.expect(42.U) // 7 * 6
    }
  }

  "MULFU" should "正确处理刷新信号" in {
    test(new MULFU) { dut =>
      // 开始乘法
      dut.io.uop.bits.instr.poke("b0000001_00010_00001_000_00000".U)
      dut.io.uop.bits.ps1_value.poke(5.U)
      dut.io.uop.bits.ps2_value.poke(3.U)
      dut.io.uop.valid.poke(true.B)

      // 1周期后发送刷新
      dut.clock.step()
      dut.io.uop.valid.poke(false.B)
      dut.io.reset.poke(true.B)
      dut.clock.step()
      dut.io.reset.poke(false.B)

      // 验证没有输出结果
      dut.out.valid.expect(false.B)
    }
  }


}
class DIVFUTest extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()

  // 除法指令类型定义 (与RISC-V func3编码一致)
  object DIVOp {
    val DIV   = 4.U(3.W)
    val DIVU  = 5.U(3.W)
    val REM   = 6.U(3.W)
    val REMU  = 7.U(3.W)
  }


  "DIVFU" should "correctly perform signed division (DIV)" in {
    test(new DIVFU) { dut =>
      // 构造指令
      dut.io.uop.bits.instr_.poke("b0000001_00010_00001_100_00000".U)
      dut.io.uop.bits.instr_type.poke(InstrType.DIV_REM)

      // 测试1: 10 / 3 = 3
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.bits.ps1_value.poke(10.U)
      dut.io.uop.bits.ps2_value.poke(3.U)
      dut.io.uop.valid.poke(true.B)
      var cycles = 0
      while (!dut.out.valid.peek().litToBoolean && cycles < 100) {
        dut.clock.step()
        cycles += 1
      }
      dut.out.valid.expect(true.B)
      dut.out.bits.pdst_value.expect(3.U)

      dut.clock.step()

      // 测试2: -10 / 3 = -3

      val minus10 = "hfffffff6".U // 转换为补码表示的UInt
      val plus3 = 3.S.asUInt
      dut.io.uop.bits.ps1_value.poke(minus10)
      dut.io.uop.bits.ps2_value.poke(plus3)
      cycles=0
      while (!dut.out.valid.peek().litToBoolean && cycles < 100) {
        dut.clock.step()
        cycles += 1
      }
      dut.out.bits.pdst_value.expect("hfffffffd".U) // -3的32位补码表示

      dut.clock.step()

      // 边界情况1: Int.MinValue / -1

      val minVal = Int.MinValue.S.asUInt // 0x80000000
      val minus1 =  "hffffffff".U     // 0xffffffff
      dut.io.uop.bits.ps1_value.poke(minVal)
      dut.io.uop.bits.ps2_value.poke(minus1)
      cycles=0
      while (!dut.out.valid.peek().litToBoolean && cycles < 100) {
        dut.clock.step()
        cycles += 1
      }
      dut.out.bits.pdst_value.expect("h80000000".U) // 应当返回Int.MinValue

      dut.clock.step()

      // 边界情况2: Int.MinValue / 1

      val plus1 = 1.S.asUInt // 0x00000001
      dut.io.uop.bits.ps1_value.poke(minVal)
      dut.io.uop.bits.ps2_value.poke(plus1)
      cycles=0
      while (!dut.out.valid.peek().litToBoolean && cycles < 100) {
        dut.clock.step()
        cycles += 1
      }
      dut.out.bits.pdst_value.expect("h80000000".U) // 应当返回Int.MinValue
}
}
  "DIVFU" should "correctly perform unsigned division (DIVU)" in {
    test(new DIVFU) { dut =>
      dut.io.uop.bits.instr_.poke("b0000001_00010_00001_101_00000".U)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.bits.instr_type.poke(InstrType.DIV_REM)

      // 测试1: 10 / 3 = 3
      dut.io.uop.bits.ps1_value.poke(10.U)
      dut.io.uop.bits.ps2_value.poke(3.U)
      dut.io.uop.valid.poke(true.B)
      var cycles = 0
      while (!dut.out.valid.peek().litToBoolean && cycles < 100) {
        dut.clock.step()
        cycles += 1
      }
      dut.out.bits.pdst_value.expect(3.U)

      // 测试2: 0xFFFFFFFF / 1 = 0xFFFFFFFF
      dut.io.uop.bits.ps1_value.poke(0xFFFFFFFF.U)
      dut.io.uop.bits.ps2_value.poke(1.U)
      while (!dut.out.valid.peek().litToBoolean && cycles < 100) {
        dut.clock.step()
        cycles += 1
      }
      dut.out.bits.pdst_value.expect(0xFFFFFFFF.U)
    }
  }
}
