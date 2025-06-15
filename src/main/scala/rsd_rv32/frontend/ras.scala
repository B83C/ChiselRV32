package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.scheduler._

class RASEntry(implicit p: Parameters) extends Bundle {
  val retAddr = UInt(p.XLEN.W)  // 返回地址
  val callPC = UInt(p.XLEN.W)   // 调用指令PC(用于验证)
}

class RAS(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    // 预测接口
    val pc = Input(UInt(p.XLEN.W))             // 当前指令PC
    val isCall = Input(Bool())                 // 是否是调用指令
    val isRet = Input(Bool())                  // 是否是返回指令
    val predRetAddr = Output(UInt(p.XLEN.W))   // 预测的返回地址
    val predValid = Output(Bool())             // 预测是否有效
    
    // 更新接口
    val update = Input(Bool())                 // 更新使能
    val updatePC = Input(UInt(p.XLEN.W))       // 更新指令PC
    val updateIsCall = Input(Bool())           // 更新是否为调用
    val updateIsRet = Input(Bool())            // 更新是否为返回
    // val updateMispred = Input(Bool())          // 更新是否预测错误
    val updateRetAddr = Input(UInt(p.XLEN.W))  // 实际返回地址(用于验证)

    val rob_commitsignal = Flipped(ROB.CommitSignal) // 用于更新branch_mask
    val rob_controlsignal = Flipped(new ROBControlSignal) // 用于更新branch_mask
  })

  // RAS参数
  val RASDepth = 8
  val ras = RegInit(VecInit(Seq.fill(RASDepth)(0.U.asTypeOf(new RASEntry))))
  val head = RegInit(0.U(log2Ceil(RASDepth).W))
  val count = RegInit(0.U((log2Ceil(RASDepth)+1).W))
  
  // 预测逻辑
  val predAddr = Mux(count > 0.U, ras(head).retAddr, 0.U)
  io.predRetAddr := predAddr
  io.predValid := count > 0.U && io.isRet
  
  // 推测性更新逻辑(前端预测时使用)
  val nextHead = MuxCase(head, Seq(
    io.isCall -> Mux(count === RASDepth.U, head, head + 1.U),
    io.isRet -> Mux(count === 0.U, head, head - 1.U)
  ))
  val nextCount = MuxCase(count, Seq(
    io.isCall -> Mux(count === RASDepth.U, count, count + 1.U),
    io.isRet -> Mux(count === 0.U, count, count - 1.U)
  ))
  
  when (io.isCall || io.isRet) {
    head := nextHead
    count := nextCount
    when (io.isCall) {
      ras(nextHead).retAddr := io.pc + 4.U  // 假设指令宽度为4字节
      ras(nextHead).callPC := io.pc
    }
  }
  
  // 提交时更新逻辑(后端确认时使用)
  when (io.update) {
    when (io.updateIsCall && !io.rob_controlsignal.shouldFlush) {
      // 确认调用指令，不需要特殊处理，因为预测时已经压栈
    }.elsewhen (io.updateIsRet) {
      when (io.rob_controlsignal.shouldFlush) {
        // 返回预测错误，需要恢复RAS状态
        head := 0.U
        count := 0.U
      }.otherwise{
        // 验证返回地址是否正确
        when (count > 0.U && ras(head).retAddr =/= io.updateRetAddr) {
          // 返回地址不匹配，清空RAS
          head := 0.U
          count := 0.U
        }
      }
    }
  }
}

// class BranchPredictorUnitWithRAS(implicit p: Parameters) extends Module {
//   val io = IO(new BP_IO())
  
//   // 实例化原始分支预测器
//   val bp = Module(new BranchPredictorUnit)
//   bp.io <> io
  
//   // 实例化RAS
//   val ras = Module(new RAS)
  
//   // RAS预测接口连接
//   ras.io.pc := io.instr_addr
//   ras.io.isCall := false.B  
//   ras.io.isRet := false.B   
  
//   // 修改目标PC选择逻辑，加入RAS预测
//   val originalTarget = bp.io.target_PC
//   val rasTarget = ras.io.predRetAddr
//   val useRAS = ras.io.predValid
  
//   // 当RAS预测有效时，优先使用RAS预测的返回地址
//   io.target_PC := Mux(useRAS, rasTarget, originalTarget)
  
//   // 更新接口连接
//   for (i <- 0 until p.CORE_WIDTH) {
//     when (io.rob_commitsignal(i).valid) {
//       val rc = io.rob_commitsignal(i).bits
//       ras.io.update := true.B
//       ras.io.updatePC := rc.instr_addr
//       ras.io.updateIsCall := rc.rob_type === ROBType.Call  
//       ras.io.updateIsRet := rc.rob_type === ROBType.Ret    
//       ras.io.updateMispred := rc.mispred
//       ras.io.updateRetAddr := rc.instr_addr + 4.U  // 实际返回地址
//     }.otherwise {
//       ras.io.update := false.B
//     }
//   }

// }
