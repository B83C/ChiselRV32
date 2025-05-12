package rsd_rv32

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import rsd_rv32.common._
import rsd_rv32.frontend.BranchPredictorUnit
import scala.util.Random

class branch_predict_test extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = Parameters()

  "BranchPredictorUnit" should "correctly predict branch instructions" in {
    test(new BranchPredictorUnit()) { c =>
      // 设置初始值
      c.io.instr_addr.poke("h0000ffff".U)  // 设置 PC 为 0xFFFF

      // 设置分支指令通过ROB提交信号
      c.io.rob_commitsignal(0).valid.poke(true.B)
      val robContent = new ROBContent()
      robContent.instr_addr := "h0000ffff".U
      robContent.rob_type := ROBType.Branch
      robContent.as_Branch.branch_direction := true.B  // 分支方向为taken
      robContent.as_Branch.target_PC := "h00001000".U  // 目标 PC 为 0x1000
      robContent.as_Branch.btb_hit := true.B
      robContent.as_Branch.GHR := 2.U  // 假设全局历史寄存器 GHR 为 2
      robContent.mispred := false.B
      c.io.rob_commitsignal(0).bits.poke(robContent)

      c.clock.step()

      // 验证更新后的预测:
      // 先取消提交信号
      c.io.rob_commitsignal(0).valid.poke(false.B)
      c.clock.step()

      // 现在使用相同的PC进行预测
      c.io.instr_addr.poke("h0000ffff".U)
      c.clock.step()

      // 检查预测的目标地址是否正确
      println(s"Predicted target PC: 0x${c.io.target_PC.peek().litValue}")
      c.io.btb_hit(0).expect(true.B)  // BTB应该命中
      c.io.branch_pred(0).expect(true.B)  // 应该预测为taken
      c.io.target_PC.expect("h00001000".U)  // 目标应该是0x1000

      println("pass!")
    }
  }

  "BranchPredictorUnit" should "correctly update after branch resolution" in {
    test(new BranchPredictorUnit()) { c =>
      // 设置初始值
      c.io.instr_addr.poke("h0000abcd".U)  // 设置 PC 为 0xABCD

      // 先通过ROB提交添加一个分支记录
      c.io.rob_commitsignal(0).valid.poke(true.B)
      val robContent = new ROBContent()
      robContent.instr_addr := "h0000abcd".U
      robContent.rob_type := ROBType.Branch
      robContent.as_Branch.branch_direction := true.B
      robContent.as_Branch.target_PC := "h00002000".U
      robContent.as_Branch.btb_hit := true.B
      robContent.as_Branch.GHR := 3.U
      robContent.mispred := false.B
      c.io.rob_commitsignal(0).bits.poke(robContent)

      c.clock.step()

      // 取消提交信号
      c.io.rob_commitsignal(0).valid.poke(false.B)
      c.clock.step()

      // 测试预测
      c.io.instr_addr.poke("h0000abcd".U)
      c.clock.step()

      // 检查预测结果
      println(s"Updated target PC: 0x${c.io.target_PC.peek().litValue}")
      c.io.btb_hit(0).expect(true.B)
      c.io.branch_pred(0).expect(true.B)
      c.io.target_PC.expect("h00002000".U)

      println("pass!")
    }
  }

  "BranchPredictorUnit" should "flush and reset state correctly" in {
    test(new BranchPredictorUnit()) { c =>
      // 设置初始值
      c.io.instr_addr.poke("h0000abcd".U)  // 设置 PC 为 0xABCD

      // 提交一个预测错误的分支
      c.io.rob_commitsignal(0).valid.poke(true.B)
      val robContent = new ROBContent()
      robContent.instr_addr := "h0000abcd".U
      robContent.rob_type := ROBType.Branch
      robContent.as_Branch.branch_direction := true.B // 实际结果是taken
      robContent.as_Branch.target_PC := "h00001000".U
      robContent.as_Branch.btb_hit := true.B
      robContent.as_Branch.GHR := 1.U
      robContent.mispred := true.B // 标记为预测错误
      c.io.rob_commitsignal(0).bits.poke(robContent)

      c.clock.step()

      // 取消提交信号
      c.io.rob_commitsignal(0).valid.poke(false.B)
      c.clock.step()

      // 测试重置后的状态
      println(s"Current GHR: ${c.io.GHR.peek().litValue}")
      // 由于mispred=true，GHR应被重置为实际分支方向，即1
      c.io.GHR.expect(1.U)

      // 测试同一地址的预测
      c.io.instr_addr.poke("h0000abcd".U)
      c.clock.step()

      println(s"Flushed target PC: 0x${c.io.target_PC.peek().litValue}")
      c.io.btb_hit(0).expect(true.B)
      c.io.branch_pred(0).expect(true.B)
      c.io.target_PC.expect("h00001000".U)

      println("pass!")
    }
  }

  "BranchPredictorUnit" should "handle multiple instructions and branches correctly" in {
    test(new BranchPredictorUnit()) { c =>
      // 首先提交第一条分支指令
      c.io.rob_commitsignal(0).valid.poke(true.B)
      val robContent1 = new ROBContent()
      robContent1.instr_addr := "h0000abcd".U
      robContent1.rob_type := ROBType.Branch
      robContent1.as_Branch.branch_direction := true.B
      robContent1.as_Branch.target_PC := "h00002000".U
      robContent1.as_Branch.btb_hit := true.B
      robContent1.as_Branch.GHR := 3.U
      robContent1.mispred := false.B
      c.io.rob_commitsignal(0).bits.poke(robContent1)

      // 同时提交第二条ALU指令
      c.io.rob_commitsignal(1).valid.poke(true.B)
      val robContent2 = new ROBContent()
      robContent2.instr_addr := "h00001234".U
      robContent2.rob_type := ROBType.Arithmetic
      robContent2.mispred := false.B
      c.io.rob_commitsignal(1).bits.poke(robContent2)

      c.clock.step()

      // 取消提交信号
      c.io.rob_commitsignal(0).valid.poke(false.B)
      c.io.rob_commitsignal(1).valid.poke(false.B)
      c.clock.step()

      // 测试预测
      c.io.instr_addr.poke("h0000abcd".U)
      c.clock.step()

      // 检查状态更新
      println(s"Predicted target PC: 0x${c.io.target_PC.peek().litValue}")
      c.io.btb_hit(0).expect(true.B)
      c.io.branch_pred(0).expect(true.B)
      c.io.target_PC.expect("h00002000".U)

      println("pass!")
    }
  }
}
