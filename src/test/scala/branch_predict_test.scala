package rsd_rv32

import chisel3._
import chiseltest._
import _root_.circt.stage.ChiselStage
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
      c.clock.step()
      // 设置分支指令通过ROB提交信号
      c.io.rob_commitsignal(0).valid.poke(true.B)
      c.io.rob_commitsignal(0).bits.instr_addr.poke("h0000ffff".U)
      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Branch)
      /*c.io.rob_commitsignal(0).bits.as_Branch.branch_direction.poke(BranchPred.T)  // 分支方向为taken
      c.io.rob_commitsignal(0).bits.as_Branch.target_PC.poke("h00001000".U)  // 目标 PC 为 0x1000
      c.io.rob_commitsignal(0).bits.as_Branch.btb_hit.poke(BTBHit.H)
      c.io.rob_commitsignal(0).bits.as_Branch.GHR.poke(2.U)*/
      // 假设全局历史寄存器 GHR 为 2
      c.io.rob_commitsignal(0).bits.payload.poke("h0_00001000_0_2".U)
      c.io.rob_commitsignal(0).bits.mispred.poke(false.B)
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
      c.io.rob_commitsignal(0).bits.instr_addr.poke("h0000abcd".U)
      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Branch)
      /*robContent.as_Branch.btb_hit.poke(BTBHit.H)
      robContent.as_Branch.target_PC.poke("h00002000".U)
      robContent.as_Branch.branch_direction.poke(BranchPred.T)
      robContent.as_Branch.GHR.poke(3.U)*/
      c.io.rob_commitsignal(0).bits.payload.poke("h0_00002000_0_3".U)
      c.io.rob_commitsignal(0).bits.mispred.poke(false.B)

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
      c.io.rob_commitsignal(0).bits.instr_addr.poke("h0000abcd".U)
      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Branch)
      /*robContent.as_Branch.btb_hit.poke(BTBHit.H)
      robContent.as_Branch.target_PC.poke("h00001000".U)
      robContent.as_Branch.branch_direction.poke(BranchPred.T) // 实际结果是taken
      robContent.as_Branch.GHR.poke(1.U)*/
      c.io.rob_commitsignal(0).bits.payload.poke("h0_00001000_0_1".U)
      c.io.rob_commitsignal(0).bits.mispred.poke(true.B) // 标记为预测错误

      c.clock.step()

      // 取消提交信号
      c.io.rob_commitsignal(0).valid.poke(false.B)
      c.clock.step()

      // 测试重置后的状态
      println(s"Current GHR: ${c.io.GHR.peek().litValue}")
      c.io.GHR.expect(3.U)

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
      c.io.rob_commitsignal(0).bits.instr_addr.poke("h0000abcd".U)
      c.io.rob_commitsignal(0).bits.rob_type.poke(ROBType.Branch)
      /*c.io.rob_commitsignal(0).bits.as_Branch.branch_direction := true.B
      c.io.rob_commitsignal(0).bits.as_Branch.target_PC.poke("h00002000".U)
      c.io.rob_commitsignal(0).bits.as_Branch.btb_hit.poke(BTBHit.H)
      c.io.rob_commitsignal(0).bits.as_Branch.GHR.poke(3.U)*/
      c.io.rob_commitsignal(0).bits.payload.poke("h0_00002000_0_3".U)
      c.io.rob_commitsignal(0).bits.mispred.poke(false.B)

      // 同时提交第二条ALU指令
      c.io.rob_commitsignal(1).valid.poke(true.B)
      c.io.rob_commitsignal(1).bits.instr_addr.poke( "h00001234".U)
      c.io.rob_commitsignal(1).bits.rob_type.poke(ROBType.Arithmetic)
      c.io.rob_commitsignal(1).bits.mispred.poke(false.B)

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
object Driver extends App {
  // 定义隐式参数实例（可自定义参数值）
  implicit val p: Parameters = Parameters()
  ChiselStage.emitSystemVerilogFile(
    new BranchPredictorUnit()
  )
  println("Verilog  已生成")
}


