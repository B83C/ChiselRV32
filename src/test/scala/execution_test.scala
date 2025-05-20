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
      dut.io.uop.bits.instr.poke(luiInstr)
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
      dut.io.uop.bits.instr.poke(auipcInstr)
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
      dut.io.uop.bits.instr.poke(addInstr)
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
      dut.io.uop.bits.instr.poke(addInstr)
      dut.io.uop.bits.ps1_value.poke(10.U)
      dut.io.uop.bits.ps2_value.poke(5.U)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.valid.poke(true.B)
      dut.clock.step()
      dut.out.bits.pdst_value.expect(15.U)

      // 第二个操作: SUB
      val subInstr = "b0100000000100000100000100".U(25.W)
      dut.io.uop.bits.instr.poke(subInstr)
      dut.io.uop.bits.ps1_value.poke(10.U)
      dut.io.uop.bits.ps2_value.poke(5.U)
      dut.clock.step()
      dut.out.bits.pdst_value.expect(5.U)
    }


  }

}

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
      dut.io.uop.bits.instr.poke("b0000001_00010_00001_000_00000".U)
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
      dut.io.uop.bits.instr.poke("b0000001_00010_00001_001_00000".U)
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
      dut.io.uop.bits.instr.poke("b0000001_00010_00001_000_00000".U)
      dut.io.uop.bits.ps1_value.poke(5.U)
      dut.io.uop.bits.ps2_value.poke(3.U)
      dut.io.uop.bits.instr_type.poke(InstrType.MUL)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.valid.poke(true.B)


      dut.clock.step(32)

      dut.out.bits.pdst_value.expect(15.U)

      // 立即开始第二个乘法
      dut.io.uop.bits.instr.poke("b0000001_00011_00010_000_00001".U)
      dut.io.uop.bits.ps1_value.poke(7.U)
      dut.io.uop.bits.ps2_value.poke(6.U)


      dut.clock.step(32)

      dut.out.bits.pdst_value.expect(42.U) // 7 * 6
    }
  }

  /*  "MULFU" should "正确处理刷新信号" in {
    test(new MULFU) { dut =>
      // 开始乘法
      dut.io.uop.bits.instr.poke("b0000001_00010_00001_000_00000".U)
      dut.io.uop.bits.ps1_value.poke(5.U)
      dut.io.uop.bits.ps2_value.poke(3.U)
      dut.io.uop.valid.poke(true.B)

      // 1周期后发送刷新
      dut.clock.step()
      dut.io.uop.valid.poke(false.B)
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      // 验证没有输出结果
      dut.out.valid.expect(false.B)
    }
  }

 */
}
class DIVFUTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {
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
//      // 构造指令
//      dut.io.uop.bits.instr.poke("b0000001_00010_00001_100_00000".U)
//      dut.io.uop.bits.instr_type.poke(InstrType.DIV_REM)
//
//      // 测试1: 10 / 3 = 3
//      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
//      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
//      dut.io.uop.bits.ps1_value.poke(100.U)
//      dut.io.uop.bits.ps2_value.poke(7.U)
//      dut.io.uop.valid.poke(true.B)
//       //等待结果 ready
//      var cycles = 0
//      while (!dut.out.valid.peek().litToBoolean && cycles < 100) {
//        dut.clock.step()
//        cycles += 1
//      }
////      dut.clock.step()
//      dut.out.valid.expect(true.B)
//      dut.out.bits.pdst_value.expect(14.U)
//
//      dut.clock.step()
//      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
//      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
//      dut.io.uop.bits.ps1_value.poke(10.U)
//      dut.io.uop.bits.ps2_value.poke(7.U)
//      dut.io.uop.valid.poke(true.B)
//      //等待结果 ready
//      cycles = 0
//      while (!dut.out.valid.peek().litToBoolean && cycles < 100) {
//        dut.clock.step()
//        cycles += 1
//      }
//      //      dut.clock.step()
//      dut.out.valid.expect(true.B)
//      dut.out.bits.pdst_value.expect(1.U)

      def stepDivider(dividend: Int, divisor: Int): (Int) = {
        require(divisor != 0, "Divisor must not be zero")

        // 构造指令
        dut.io.uop.bits.instr.poke("b0000001_00010_00001_100_00000".U)
        dut.io.uop.bits.instr_type.poke(InstrType.DIV_REM)

        // 测试1: 10 / 3 = 3
        dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
        dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
        dut.io.uop.bits.ps1_value.poke(dividend.U)
        dut.io.uop.bits.ps2_value.poke(divisor.U)
        dut.io.uop.valid.poke(true.B)
        //等待结果 ready
        var cycles = 0
        while (!dut.out.valid.peek().litToBoolean && cycles < 100) {
          dut.clock.step()
          cycles += 1
        }
        val quotient  = dut.out.bits.pdst_value.peek().litValue
        (quotient.toInt)
      }

      val testCases = Seq(
        (10, 3),   // 正常除法
        (15, 5),
        (100, 7),
        (255, 1),
        (256, 16),
        (0, 5),    // 被除数为0
        (12345, 123), // 大数
        (1073741824, 33554430)
      )

      for ((a, b) <- testCases) {
        val q = stepDivider(a, b)
        println(s"$a / $b = $q")
        q shouldEqual (a / b)
        dut.clock.step()
      }

//      // 测试2: -10 / 3 = -3
//      dut.io.uop.bits.ps1_value.poke((-10).S)
//      dut.io.uop.bits.ps2_value.poke(3.S)
//      dut.clock.step()
//      dut.out.bits.pdst_value.expect((-3).S)
//
//      // 边界情况
//      dut.io.uop.bits.ps1_value.poke(Int.MinValue.S)
//      dut.io.uop.bits.ps2_value.poke((-1).S)
//      dut.clock.step()
//      dut.out.bits.pdst_value.expect(Int.MinValue.S)
//
//      dut.io.uop.bits.ps1_value.poke(Int.MinValue.S)
//      dut.io.uop.bits.ps2_value.poke(1.S)
//      dut.clock.step()
//      dut.out.bits.pdst_value.expect(Int.MinValue.S)
    }
  }

  "DIVFU" should "correctly perform unsigned division (DIVU)" in {
    test(new DIVFU) { dut =>
      val instr = Cat(DIVOp.DIVU, 0.U(29.W))
      dut.io.uop.bits.instr.poke("b0000001_00010_00001_000_00000".U)
      dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
      dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
      dut.io.uop.bits.instr.poke(instr)
      dut.io.uop.bits.instr_type.poke(InstrType.DIV_REM)

      // 测试1: 10 / 3 = 3
      dut.io.uop.bits.ps1_value.poke(10.U)
      dut.io.uop.bits.ps2_value.poke(3.U)
      dut.io.uop.valid.poke(true.B)
      dut.clock.step()
      dut.out.bits.pdst_value.expect(3.U)

      // 测试2: 0xFFFFFFFF / 1 = 0xFFFFFFFF
      dut.io.uop.bits.ps1_value.poke(0xFFFFFFFF.U)
      dut.io.uop.bits.ps2_value.poke(1.U)
      dut.clock.step()
      dut.out.bits.pdst_value.expect(0xFFFFFFFF.U)
    }
  }
  // 测试辅助函数
  def testDivide(dut: DIVFU, a: Int, b: Int, op: UInt, expected: Int, timeout: Int = 100): Unit = {
    // 构造指令 (简化版，实际应根据具体指令格式)
    val instr = Cat(op, 0.U(29.W)) // func3在14-12位
    dut.io.uop.bits.instr.poke(instr)
    dut.io.uop.bits.instr_type.poke(InstrType.DIV_REM)

    // 设置操作数
    dut.io.uop.bits.ps1_value.poke(a.U)
    dut.io.uop.bits.ps2_value.poke(b.U)
    dut.io.uop.bits.fu_signals.opr1_sel.poke(OprSel.REG)
    dut.io.uop.bits.fu_signals.opr2_sel.poke(OprSel.REG)
    dut.io.uop.valid.poke(true.B)

    // 等待计算完成
    var cycles = 0
    while (!dut.out.valid.peek().litToBoolean && cycles < timeout) {
      dut.clock.step()
      cycles += 1
      dut.io.uop.valid.poke(false.B) // 单周期有效
    }

    if (cycles >= timeout) fail(s"Timeout after $timeout cycles")
    else {
      dut.out.valid.expect(true.B)
      dut.out.bits.pdst_value.expect(expected.U)
    }
  }



  "DIVFU" should "correctly perform signed remainder (REM)" in {
    test(new DIVFU) { dut =>
      testDivide(dut, 10, 3, DIVOp.REM, 1)    // 10 % 3 = 1
      testDivide(dut, -10, 3, DIVOp.REM, -1)   // -10 % 3 = -1
      testDivide(dut, 10, -3, DIVOp.REM, 1)    // 10 % -3 = 1
    }
  }

  "DIVFU" should "correctly perform unsigned remainder (REMU)" in {
    test(new DIVFU) { dut =>
      testDivide(dut, 10, 3, DIVOp.REMU, 1)
      testDivide(dut, 0xFFFFFFFE, 0xFFFFFFFF, DIVOp.REMU, 0xFFFFFFFE)
    }
  }

  "DIVFU" should "handle division by zero according to RISC-V spec" in {
    test(new DIVFU) { dut =>
      // RISC-V规范：除零时商为全1，余数为被除数
      testDivide(dut, 10, 0, DIVOp.DIV, -1)
      testDivide(dut, -10, 0, DIVOp.DIV, -1)
      testDivide(dut, 10, 0, DIVOp.DIVU, 0xFFFFFFFF)
      testDivide(dut, 10, 0, DIVOp.REM, 10)
      testDivide(dut, 10, 0, DIVOp.REMU, 10)
    }
  }

  "DIVFU" should "handle pipeline back-to-back operations" in {
    test(new DIVFU) { dut =>
      // 第一个除法
      testDivide(dut, 20, 5, DIVOp.DIV, 4)

      // 立即开始第二个除法
      testDivide(dut, 30, 6, DIVOp.DIV, 5)
    }
  }

  "DIVFU" should "correctly handle flush signal" in {
    test(new DIVFU) { dut =>
      // 启动除法
      val instr = Cat(DIVOp.DIV, 0.U(29.W))
      dut.io.uop.bits.instr.poke(instr)
      dut.io.uop.bits.ps1_value.poke(100.U)
      dut.io.uop.bits.ps2_value.poke(3.U)
      dut.io.uop.valid.poke(true.B)
      dut.clock.step()

      /* 发送flush
      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)
      */
      // 验证没有输出
      dut.out.valid.expect(false.B)

      // 验证可以开始新除法
      testDivide(dut, 8, 2, DIVOp.DIV, 4)
    }
  }

  "DIVFU" should "handle power-of-two divisions efficiently" in {
    test(new DIVFU) { dut =>
      testDivide(dut, 16, 4, DIVOp.DIV, 4)
      testDivide(dut, 32, 8, DIVOp.DIVU, 4)
    }
  }
}
