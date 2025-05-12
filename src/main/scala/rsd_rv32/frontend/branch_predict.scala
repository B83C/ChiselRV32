package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._

/*class BP_IFU_Interface(implicit p: Parameters) extends CustomBundle {
    val PC_cur = Input(UInt(p.XLEN.W)) //当前IFU的PC值
    val target_PC = Output(UInt(p.XLEN.W)) //预测的下个cycle取指的目标地址
    val btb_hit = Output(Vec(p.CORE_WIDTH, Bool())) //1代表hit，0相反；将最年轻的命中BTB的置为1，其余为0
    val branch_pred = Output(Bool()) //branch指令的BHT的预测结果；1代表跳转，0相反
    val GHR = Output(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照
}

class BP_ROB_Interface(implicit p: Parameters) extends CustomBundle {
    val PC = Input(UInt(p.XLEN.W)) //当前ROB的PC值
    val instrType = Input(UInt(3.W)) //当前指令类型,该模块需要区分条件分支和无条件分支
    val btb_hit = Input(Bool()) //该分支指令最初是否命中BTB
    val actual_Taken = Input(Bool()) //实际是否taken
    val GHR = Input(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照，使得更新BHT时能够生成正确的index
    val actualTargetPC = Input(UInt(p.XLEN.W)) //目标地址
} */

class BP_IO (implicit p: Parameters) extends CustomBundle {
  // With IF
  val instr_addr = Input(UInt(p.XLEN.W))  // 当前PC值，双发射下指向第一条指令的PC
  val target_PC = Output(UInt(p.XLEN.W))  // 预测的下个cycle取指的目标地址
  val btb_hit = Output(Vec(p.CORE_WIDTH, Bool()))  // 每条指令是否命中BTB
  val branch_pred = Output(Vec(p.CORE_WIDTH, Bool()))  // 每条指令的预测结果
  val GHR = Output(UInt(p.GHR_WIDTH.W))  // 作出预测时的GHR快照

  // With ROB
  val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent())))  // ROB提交时的广播信号
}

class BranchPredictorUnit(implicit p: Parameters) extends Module {
  // 参数定义
  val bimodeTableSize = 1024         // T/NT 表大小
  val choiceTableSize = 1024         // 选择器表大小
  val counterBits = 2                // 饱和计数器位宽
  val btbSize = 512                  // BTB 表大小
  val instBytes = 4                  // 指令字节宽度

  val io = IO(new BP_IO())

  // ---------------------------------------------------------------------------
  // 1. BTB 部分
  // ---------------------------------------------------------------------------
  class BTBEntry extends Bundle {
    val valid = Bool()                // 有效位
    val target = UInt(p.XLEN.W)       // 跳转目标地址
    val isConditional = Bool()        // 是否条件分支
    val tag = UInt(21.W)              // PC 高位标签
  }

  // BTB表存储
  val btb = Mem(btbSize, new BTBEntry)

  // 计算索引和标签
  def btbIndex(pc: UInt): UInt = pc(log2Ceil(btbSize) + log2Ceil(instBytes) - 1, log2Ceil(instBytes))
  def btbTag(pc: UInt): UInt = pc(p.XLEN-1, log2Ceil(instBytes) + log2Ceil(btbSize))

  // 双发射下访问连续两条指令的地址
  val pc0 = io.instr_addr  // 第一条指令PC
  val pc1 = io.instr_addr + instBytes.U  // 第二条指令PC

  // 分别计算两条指令的BTB索引和标签
  val btbIdx0 = btbIndex(pc0)
  val btbTag0 = btbTag(pc0)
  val btbIdx1 = btbIndex(pc1)
  val btbTag1 = btbTag(pc1)

  // 读取两条指令对应的BTB表项
  val rawEntry0 = RegNext(btb.read(btbIdx0))
  val rawEntry1 = RegNext(btb.read(btbIdx1))

  // 判断BTB是否命中
  val btbHit0 = rawEntry0.valid && rawEntry0.tag === btbTag0
  val btbHit1 = rawEntry1.valid && rawEntry1.tag === btbTag1

  // ---------------------------------------------------------------------------
  // 2. Bi-Mode BHT 部分 - 改进：独立预测两条指令
  // ---------------------------------------------------------------------------
  // 全局历史寄存器
  val ghr = RegInit(0.U(p.GHR_WIDTH.W))
  io.GHR := ghr  // 输出当前GHR值

  // T/NT表和选择器
  val bimodeT = Mem(bimodeTableSize, UInt(counterBits.W))
  val bimodeNT = Mem(bimodeTableSize, UInt(counterBits.W))
  val choice = Mem(choiceTableSize, UInt(counterBits.W))

  // 计算两条指令的索引
  val histIdx0 = (pc0 ^ ghr)(log2Ceil(bimodeTableSize)-1, 0)
  val tagIdx0 = pc0(log2Ceil(choiceTableSize)-1, 0)

  // 改进：第二条指令的GHR需要考虑第一条指令可能造成的更新
  // 假设第一条指令预测为taken，则GHR向左移动1位，并在最低位填入1
  val specGHR_taken = Cat(ghr(p.GHR_WIDTH-2, 0), 1.U(1.W))
  // 假设第一条指令预测为not taken，则GHR向左移动1位，并在最低位填入0
  val specGHR_notTaken = Cat(ghr(p.GHR_WIDTH-2, 0), 0.U(1.W))

  // 使用两种可能的GHR状态分别预测第二条指令
  val histIdx1_taken = (pc1 ^ specGHR_taken)(log2Ceil(bimodeTableSize)-1, 0)
  val histIdx1_notTaken = (pc1 ^ specGHR_notTaken)(log2Ceil(bimodeTableSize)-1, 0)
  val tagIdx1 = pc1(log2Ceil(choiceTableSize)-1, 0)

  // 读取预测表和选择器的值 - 第一条指令
  val cntT0 = RegNext(bimodeT.read(histIdx0))
  val cntNT0 = RegNext(bimodeNT.read(histIdx0))
  val sel0 = RegNext(choice.read(tagIdx0))

  // 读取预测表和选择器的值 - 第二条指令（两种可能的历史）
  // 假设第一条指令taken
  val cntT1_taken = bimodeT.read(histIdx1_taken)
  val cntNT1_taken = bimodeNT.read(histIdx1_taken)
  // 假设第一条指令not taken
  val cntT1_notTaken = bimodeT.read(histIdx1_notTaken)
  val cntNT1_notTaken = bimodeNT.read(histIdx1_notTaken)

  val sel1 = choice.read(tagIdx1)

  // 进行Bi-Mode预测 - 第一条指令
  val useNT0 = sel0(counterBits-1)
  val predBit0 = Mux(useNT0, cntNT0(counterBits-1), cntT0(counterBits-1))

  // 进行Bi-Mode预测 - 第二条指令（两种可能的历史）
  val useNT1 = sel1(counterBits-1)
  val predBit1_taken = Mux(useNT1, cntNT1_taken(counterBits-1), cntT1_taken(counterBits-1))
  val predBit1_notTaken = Mux(useNT1, cntNT1_notTaken(counterBits-1), cntT1_notTaken(counterBits-1))

  // ---------------------------------------------------------------------------
  // 3. 最终预测逻辑 - 改进：独立预测两条指令
  // ---------------------------------------------------------------------------
  // 计算两条指令的原始预测结果
  val rawPred0 = Wire(Bool())  // 改进：记录原始预测结果
  val pred0 = Wire(Bool())     // 最终预测结果
  val fallThrough0 = Wire(Bool())  // 是否继续执行下一条指令

  // 设置默认值
  rawPred0 := false.B
  pred0 := false.B
  fallThrough0 := true.B

  when (btbHit0) {
    when (!rawEntry0.isConditional) {
      // 无条件跳转：总是预测为跳转
      rawPred0 := true.B
      pred0 := true.B
      fallThrough0 := false.B
    }.otherwise {
      // 条件分支：根据方向预测器决定
      rawPred0 := predBit0
      pred0 := predBit0
      fallThrough0 := !predBit0  // 只有当预测不跳转时才继续执行下一条
    }
  }.otherwise {
    // BTB未命中：假设为顺序执行
    rawPred0 := false.B
    pred0 := false.B
    fallThrough0 := true.B
  }

  // 第二条指令的预测 - 改进：独立完成预测，然后根据第一条指令结果选择使用哪个
  val rawPred1 = Wire(Bool())  // 改进：记录原始预测结果
  val pred1 = Wire(Bool())
  val fallThrough1 = Wire(Bool())

  // 设置默认值
  rawPred1 := false.B
  pred1 := false.B
  fallThrough1 := true.B

  // 无论第一条指令预测如何，都完成第二条指令的预测
  when (btbHit1) {
    when (!rawEntry1.isConditional) {
      // 无条件跳转：总是预测为跳转
      rawPred1 := true.B
      // 最终是否使用这个预测结果，取决于第一条指令
      pred1 := true.B && fallThrough0
      fallThrough1 := false.B
    }.otherwise {
      // 条件分支：根据第一条指令的预测结果选择使用哪个历史
      val predBit1 = Mux(rawPred0, predBit1_taken, predBit1_notTaken)
      rawPred1 := predBit1
      // 最终是否使用这个预测结果，取决于第一条指令
      pred1 := predBit1 && fallThrough0
      fallThrough1 := !predBit1
    }
  }.otherwise {
    // BTB未命中：假设为顺序执行
    rawPred1 := false.B
    pred1 := false.B
    fallThrough1 := true.B
  }

  // 计算最终跳转地址 - 改进：更明确的优先级处理
  val targetPC = Wire(UInt(p.XLEN.W))
  when (pred0) {
    // 第一条指令预测跳转，使用其目标地址
    targetPC := rawEntry0.target
  }.elsewhen (pred1) {
    // 第一条不跳转但第二条跳转，使用第二条的目标地址
    targetPC := rawEntry1.target
  }.otherwise {
    // 两条指令都不跳转，顺序取下两条指令
    targetPC := io.instr_addr + (instBytes * 2).U
  }

  // BTB命中向量 - 改进：完整记录命中情况
  val btbHitVec = Wire(Vec(p.CORE_WIDTH, Bool()))
  btbHitVec(0) := btbHit0
  btbHitVec(1) := btbHit1  // 无条件记录第二条指令的BTB命中情况

  // 分支预测结果向量 - 改进：同时记录原始预测和考虑控制流的最终预测
  val rawBranchPredVec = Wire(Vec(p.CORE_WIDTH, Bool()))  // 原始预测结果
  rawBranchPredVec(0) := rawPred0
  rawBranchPredVec(1) := rawPred1

  val branchPredVec = Wire(Vec(p.CORE_WIDTH, Bool()))  // 最终预测结果
  branchPredVec(0) := pred0
  branchPredVec(1) := pred1  // 已经在计算pred1时考虑了fallThrough0

  // 改进：更精确的GHR更新逻辑
  val nextGHR = Wire(UInt(p.GHR_WIDTH.W))

  // 严格按照程序顺序更新GHR，但考虑双发射的两种情况
  when (pred0) {
    // 第一条指令预测跳转，GHR移入1位，忽略第二条指令（因为不会执行）
    nextGHR := Cat(ghr(p.GHR_WIDTH-2, 0), 1.U(1.W))
  }.elsewhen (pred1) {
    // 第一条不跳转(移入0)，第二条跳转(移入1)
    nextGHR := Cat(ghr(p.GHR_WIDTH-3, 0), 0.U(1.W), 1.U(1.W))
  }.otherwise {
    // 两条指令都不跳转，移入两个0
    nextGHR := Cat(ghr(p.GHR_WIDTH-3, 0), 0.U(1.W), 0.U(1.W))
  }

  // 流水线寄存器
  val targetPCReg = RegNext(targetPC)
  val btbHitVecReg = RegNext(btbHitVec)
  val branchPredVecReg = RegNext(branchPredVec)
  val nextGHRReg = RegNext(nextGHR)

  // 更新推测性GHR
  ghr := nextGHRReg

  // 设置输出
  io.target_PC := targetPCReg
  io.btb_hit := btbHitVecReg
  io.branch_pred := branchPredVecReg

  // ---------------------------------------------------------------------------
  // 4. 更新逻辑：处理ROB提交的分支结果 - 改进：更精确的饱和计数器更新，增强Jump指令处理
  // ---------------------------------------------------------------------------
  for (i <- 0 until p.CORE_WIDTH) {
    when (io.rob_commitsignal(i).valid) {
      val rc = io.rob_commitsignal(i).bits
      val pc = rc.instr_addr

      when (rc.rob_type === ROBType.Branch || rc.rob_type === ROBType.Jump) {
        // 获取实际跳转结果
        val taken = MuxCase(false.B, Seq(
          (rc.rob_type === ROBType.Branch) -> (rc.as_Branch.branch_direction === BranchPred.T),
          (rc.rob_type === ROBType.Jump) -> true.B  // Jump指令总是taken
        ))

        // 获取实际目标地址
        val target = MuxCase(0.U, Seq(
          (rc.rob_type === ROBType.Branch) -> rc.as_Branch.target_PC,
          (rc.rob_type === ROBType.Jump) -> rc.as_Jump.target_PC
        ))

        // 获取预测时的GHR快照
        val ghr_snapshot = MuxCase(ghr, Seq(
          (rc.rob_type === ROBType.Branch) -> rc.as_Branch.GHR,
          // Jump指令没有存储GHR快照，使用当前GHR作为近似值
          (rc.rob_type === ROBType.Jump) -> ghr  // 简化处理
        ))

        // 获取BTB命中信息
        val btb_hit = MuxCase(false.B, Seq(
          (rc.rob_type === ROBType.Branch) -> rc.as_Branch.btb_hit,
          (rc.rob_type === ROBType.Jump) -> rc.as_Jump.btb_hit
        ))

        // 更新BTB - 改进：始终更新BTB
        val btbIdx = btbIndex(pc)
        val btbTag = btbTag(pc)

        val newBtbEntry = Wire(new BTBEntry)
        newBtbEntry.valid := true.B
        newBtbEntry.target := target
        // 条件分支的isConditional为true，Jump指令的isConditional为false
        newBtbEntry.isConditional := rc.rob_type === ROBType.Branch
        newBtbEntry.tag := btbTag

        // BTB更新策略：对所有分支类型都更新BTB
        btb.write(btbIdx, newBtbEntry)

        // 更新Bi-Mode预测器 - 改进：只对条件分支更新模式预测器
        when (rc.rob_type === ROBType.Branch) {
          // 计算与预测时相同的索引
          val histIdx = (pc ^ ghr_snapshot)(log2Ceil(bimodeTableSize)-1, 0)
          val tagIdx = pc(log2Ceil(choiceTableSize)-1, 0)

          // 读取当前预测状态
          val tValue = bimodeT.read(histIdx)
          val ntValue = bimodeNT.read(histIdx)
          val chValue = choice.read(tagIdx)

          // 判断当前使用T表还是NT表
          val usedNT = chValue(counterBits-1)

          // 获取两个表的预测结果
          val tPred = tValue(counterBits-1)
          val ntPred = ntValue(counterBits-1)

/*        // 根据实际跳转结果更新对应的预测表(需要修改)
          when (usedNT) {
            // 如果选择了NT表，则更新NT表
            bimodeNT.write(histIdx, satUpdate(ntValue, taken===(0.U)))
          }.otherwise {
            // 否则更新T表
            bimodeT.write(histIdx, satUpdate(tValue, taken===(0.U)))
          }
*/
          // 根据PDF中的Bi-Mode算法更新选择器
          // 选择器的更新策略是：当预测正确时，强化当前选择；当预测错误时，减弱当前选择
          val predUsedTable = Mux(usedNT, ntPred, tPred)  // 获取使用的表的预测结果
          val predCorrect = predUsedTable === taken        // 判断预测是否正确

          // 更新选择器
          val newChoice = Mux(predCorrect,
            // 如果预测正确，强化当前选择（如果当前选NT表，则选择器向NT方向移动；如果当前选T表，则选择器向T方向移动）
            satUpdate(chValue, usedNT),
            // 如果预测错误，减弱当前选择（如果当前选NT表，则选择器向T方向移动；如果当前选T表，则选择器向NT方向移动）
            satUpdate(chValue, !usedNT)
          )

          choice.write(tagIdx, newChoice)
          // 根据实际跳转结果更新对应的预测表
          when (newChoice(counterBits-1)) {
            // 如果以后选择T表，则更新T表
            bimodeT.write(histIdx, satUpdate(tValue, taken))
          }.otherwise {
            // 否则更新NT表
            bimodeNT.write(histIdx, satUpdate(ntValue, taken))
          }
        }
        // 如果预测错误，恢复GHR - 对所有分支类型（包括Jump）处理
        when (rc.mispred) {
          // 根据提交的指令位置和实际结果重建GHR
          val instr_idx = i.U  // 当前指令在提交宽度中的索引

          // 通过指令提交位置计算需要移位的位数
          val shift_amt = instr_idx + 1.U

          // 根据实际结果恢复GHR
          when (shift_amt >= p.GHR_WIDTH.U) {
            // 如果移位量超过GHR宽度，则只保留最新的结果
            ghr := taken.asUInt
          }.otherwise {
            // 否则按位置插入实际结果
            val mask1 = 1.U << shift_amt
            val mask = mask1.asUInt - 1.U
            val preserved_bits = ghr >> shift_amt
            val new_bits = Cat(Fill((shift_amt - 1.U).asUInt.litValue.toInt, 0.U(1.W)), taken.asUInt)
            ghr := (preserved_bits << shift_amt).asUInt | new_bits
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 饱和计数器更新函数
  // ---------------------------------------------------------------------------
  def satUpdate(cur: UInt, taken: Bool): UInt = {
    val max_val = ((1 << counterBits) - 1).U
    val min_val = 0.U

    Mux(taken,
      // 如果跳转: 增加计数器，但不超过最大值
      Mux(cur === max_val, max_val, cur + 1.U),
      // 如果不跳转: 减少计数器，但不低于最小值
      Mux(cur === min_val, min_val, cur - 1.U)
    )
  }

  // 初始化内存
  when ((System.getProperty("RANDOMIZE_MEM_INIT") != null).asBool) {
    for (i <- 0 until bimodeTableSize) {
      bimodeT(i) := 2.U(counterBits.W) // T表默认初始化为2'b11 (强取)
      bimodeNT(i) := 2.U(counterBits.W) // NT表默认初始化为2'b10 (弱不取)
    }
    for (i <- 0 until choiceTableSize) {
      choice(i) := 2.U(counterBits.W) // 选择器默认为2'b10 (弱选择NT表)
    }
  }
}
object GenerateVerilog extends App {
  // 导入项目自定义参数类
  import rsd_rv32.common.Parameters

  // 定义隐式参数实例（可自定义参数值）
  implicit val p: Parameters = Parameters(
  )

  // 生成 Verilog
  (new chisel3.stage.ChiselStage).emitVerilog(
    new BranchPredictorUnit()(p), // 传递隐式参数
    Array(
      "--target-dir", "generated/verilog",
      "--full-stacktrace"          // 可选：生成详细错误信息
    )
  )
}
