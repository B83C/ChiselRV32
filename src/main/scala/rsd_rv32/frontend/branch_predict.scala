package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._

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
  val initCounter = RegInit(0.U(log2Ceil(bimodeTableSize + 1).W))
  val initialized = RegInit(false.B)  // 初始化完成标志

  val bimodeT = Mem(bimodeTableSize, UInt(counterBits.W))
  val bimodeNT = Mem(bimodeTableSize, UInt(counterBits.W))
  val choice = Mem(choiceTableSize, UInt(counterBits.W))
  // 初始化内存
  when (reset.asBool) {
    for (i <- 0 until bimodeTableSize) {
      bimodeT(i) := 2.U(counterBits.W)  // T表初始化为2'b10 (弱预测跳转)
      bimodeNT(i) := 1.U(counterBits.W) // NT表初始化为2'b01 (弱预测不跳转)
    }
    for (i <- 0 until choiceTableSize) {
      choice(i) := 2.U(counterBits.W)   // 选择器初始化为2'b10 (弱选择NT表)
    }
  }
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
  val pc0 = io.instr_addr            // 第一条指令PC
  val pc1 = io.instr_addr + instBytes.U  // 第二条指令PC

  // 分别计算两条指令的BTB索引和标签
  val btbIdx0 = btbIndex(pc0)
  val btbTag0 = btbTag(pc0)
  val btbIdx1 = btbIndex(pc1)
  val btbTag1 = btbTag(pc1)

  // 读取两条指令对应的BTB表项
  val btbEntry0 = btb.read(btbIdx0)
  val btbEntry1 = btb.read(btbIdx1)
/*
  // 流水线寄存器 - 记录BTB查询结果
  val btbEntry0_reg = RegNext(btbEntry0)
  val btbEntry1_reg = RegNext(btbEntry1)
  val btbTag0_reg = RegNext(btbTag0)
  val btbTag1_reg = RegNext(btbTag1)
*/
  // 判断BTB是否命中
  val btbHit0 = btbEntry0.valid && btbEntry0.tag === btbTag0
  val btbHit1 = btbEntry1.valid && btbEntry1.tag === btbTag1

  // ---------------------------------------------------------------------------
  // 2. 全局历史寄存器(GHR)
  // ---------------------------------------------------------------------------
  val ghr = RegInit(0.U(p.GHR_WIDTH.W))
  // 输出当前GHR值作为预测时的快照
  io.GHR := ghr

  // ---------------------------------------------------------------------------
  // 3. Bi-Mode BHT 部分
  // ---------------------------------------------------------------------------
  // T/NT表和选择器
  /*val bimodeT = Mem(bimodeTableSize, UInt(counterBits.W))
  val bimodeNT = Mem(bimodeTableSize, UInt(counterBits.W))
  val choice = Mem(choiceTableSize, UInt(counterBits.W))
*/
  // 计算预测索引 - 第一条指令
  val histIdx0 = (pc0 ^ ghr)(log2Ceil(bimodeTableSize)-1, 0)
  val tagIdx0 = pc0(log2Ceil(choiceTableSize)-1, 0)

  // 读取预测表和选择器的值
  val tValue0 = bimodeT.read(histIdx0)
  val ntValue0 = bimodeNT.read(histIdx0)
  val choiceValue0 = choice.read(tagIdx0)
/*
  // 流水线寄存器 - 预测器状态
  val tValue0_reg = RegNext(tValue0)
  val ntValue0_reg = RegNext(ntValue0)
  val choiceValue0_reg = RegNext(choiceValue0)
*/
  // 预测第一条指令
  val useNT0 = choiceValue0(counterBits-1)
  val predBit0 = Mux(useNT0, tValue0(counterBits-1), ntValue0(counterBits-1))
  // 为第二条指令计算推测性GHR
  val specGHR = Cat(ghr(p.GHR_WIDTH-2, 0), predBit0)
  // 计算预测索引 - 第二条指令（使用推测性GHR）
  val histIdx1 = (pc1 ^ specGHR)(log2Ceil(bimodeTableSize)-1, 0)
  val tagIdx1 = pc1(log2Ceil(choiceTableSize)-1, 0)
  // 读取第二条指令的预测表和选择器值
  val tValue1 = bimodeT.read(histIdx1)
  val ntValue1 = bimodeNT.read(histIdx1)
  val choiceValue1 = choice.read(tagIdx1)
/*
  // 流水线寄存器 - 第二条指令预测器状态
  val tValue1_reg = RegNext(tValue1)
  val ntValue1_reg = RegNext(ntValue1)
  val choiceValue1_reg = RegNext(choiceValue1)
*/
  // 预测第二条指令
  val useNT1 = choiceValue1(counterBits-1)
  val predBit1 = Mux(useNT1, tValue1(counterBits-1), ntValue1(counterBits-1))

  // ---------------------------------------------------------------------------
  // 4. 最终预测逻辑
  // ---------------------------------------------------------------------------
  // 第一条指令的预测结果
  val pred0 = Wire(Bool())
  val pred0_valid = Wire(Bool())  // 标记第一条指令预测是否有效

  // 默认值
  pred0 := false.B
  pred0_valid := false.B

  when (btbHit0) {
    pred0_valid := true.B
    when (!btbEntry0.isConditional) {
      // 无条件跳转：总是预测为跳转
      pred0 := true.B
    }.otherwise {
      // 条件分支：根据方向预测器决定
      pred0 := predBit0
    }
  }
  // 第二条指令的预测结果
  val pred1 = Wire(Bool())
  val pred1_valid = Wire(Bool())  // 标记第二条指令预测是否有效

  // 默认值
  pred1 := false.B
  pred1_valid := false.B

  // 只有当第一条指令预测为不跳转时，才考虑第二条指令
  when (!pred0 && btbHit1) {
    pred1_valid := true.B
    when (!btbEntry1.isConditional) {
      // 无条件跳转：总是预测为跳转
      pred1 := true.B
    }.otherwise {
      // 条件分支：根据方向预测器决定
      pred1 := predBit1
    }
  }
  // 计算最终跳转目标
  val targetPC = Wire(UInt(p.XLEN.W))
  when (pred0) {
    // 第一条指令预测跳转，使用其目标地址
    targetPC := btbEntry0.target
  }.elsewhen (pred1) {
    // 第一条不跳转但第二条跳转，使用第二条的目标地址
    targetPC := btbEntry1.target
  }.otherwise {
    // 两条指令都不跳转，顺序取下两条指令
    targetPC := io.instr_addr + (instBytes * 2).U
  }
  
  // 输出BTB命中和分支预测结果
  val btbHitVec = Wire(Vec(p.CORE_WIDTH, Bool()))
  btbHitVec(0) := btbHit0
  btbHitVec(1) := btbHit1
  printf(p"btbHitVec(0): ${btbHitVec(0)}\n")
  printf(p"btbHitVec(1): ${btbHitVec(1)}\n")
  val branchPredVec = Wire(Vec(p.CORE_WIDTH, Bool()))
  branchPredVec(0) := pred0 && pred0_valid
  branchPredVec(1) := pred1 && pred1_valid
  
  // 更新推测性GHR
  val nextGHR = Wire(UInt(p.GHR_WIDTH.W))
  when (pred0) {
    // 第一条指令预测跳转，GHR向左移入1
    nextGHR := Cat(ghr(p.GHR_WIDTH-2, 0), 1.U(1.W))
  }.elsewhen (pred1) {
    // 第一条不跳转(0)，第二条跳转(1)
    nextGHR := Cat(ghr(p.GHR_WIDTH-3, 0), 0.U(1.W), 1.U(1.W))
  }.otherwise {
    // 两条指令都不跳转，移入两个0
    nextGHR := Cat(ghr(p.GHR_WIDTH-3, 0), 0.U(1.W), 0.U(1.W))
  }
  // 更新GHR
  ghr := nextGHR
  // 设置输出
  io.target_PC := targetPC
  io.btb_hit := btbHitVec
  io.branch_pred := branchPredVec

  // ---------------------------------------------------------------------------
  // 5. 更新逻辑：处理ROB提交的分支结果
  // ---------------------------------------------------------------------------
  // 处理每个提交的指令
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
        printf(p"taken: $taken\n")
        // 获取实际目标地址
        val target = MuxCase(0.U, Seq(
          (rc.rob_type === ROBType.Branch) -> rc.as_Branch.target_PC,
          (rc.rob_type === ROBType.Jump) -> rc.as_Jump.target_PC
        ))

        // 获取预测时的GHR快照
        val ghr_snapshot = MuxCase(ghr, Seq(
          (rc.rob_type === ROBType.Branch) -> rc.as_Branch.GHR,
          (rc.rob_type === ROBType.Jump) -> ghr  // Jump指令简化处理
        ))
        // 更新BTB
        val btbIdx = btbIndex(pc)
        val btbtag = btbTag(pc)
        val newBtbEntry = Wire(new BTBEntry)
        newBtbEntry.valid := true.B
        newBtbEntry.target := target << 1
        newBtbEntry.isConditional := rc.rob_type === ROBType.Branch
        newBtbEntry.tag := btbtag
        btb.write(btbIdx, newBtbEntry)

        // 只对条件分支更新Bi-Mode预测器
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
          // 计算预测结果
          val predUsedTable = Mux(usedNT, tPred, ntPred)
          val predCorrect = predUsedTable === taken
          printf(p"predCorrect: ${predCorrect}\n")
          // 更新选择器 - 修正逻辑
          val newChoice = Mux(tPred === ntPred,
            // 如果T表和NT表预测相同，保持选择器不变
            chValue,
            Mux(predCorrect,
              // 如果预测正确，强化当前选择
              satUpdate(chValue, usedNT),
              // 如果预测错误，减弱当前选择
              satUpdate(chValue, !usedNT)
            )
          )
          choice.write(tagIdx, newChoice)
          // 更新预测表 - T表总是被训练为预测跳转，NT表总是被训练为预测不跳转
          bimodeT.write(histIdx, satUpdate(tValue, taken))
          bimodeNT.write(histIdx, satUpdate(ntValue, !taken))
        }
        
        // 如果预测错误，恢复GHR
        when (rc.mispred) {
          // 计算正确的GHR更新
          // 首先考虑当前指令在提交窗口中的位置
          val shift_amt = i.U + 1.U

          // 计算需要保留的高位和需要插入的低位
          val high_bits = ghr >> shift_amt

          // 根据实际的跳转结果更新低位
          when (shift_amt >= p.GHR_WIDTH.U) {
            // 如果移位量超过GHR宽度，则整个GHR都是推测性的
            ghr := Mux(taken, 1.U, 0.U)
          }.otherwise {
            // 左移高位，并将实际结果插入最低位
/*            val mask = (1.U << shift_amt).asUInt - 1.U  // 生成位宽为 shift_amt 的掩码
            val preserved_bits = ghr >> shift_amt
            val new_bits = taken.asUInt & mask    // 保留最低位，高位清零
            ghr := (preserved_bits << shift_amt).asUInt | new_bits*/
            val new_ghr = (high_bits << shift_amt).asUInt |
              Mux(taken, (1.U << (shift_amt - 1.U)), 0.U).asUInt
            ghr := new_ghr
          }
        }
      }
    }
  }
  // ---------------------------------------------------------------------------
  // 饱和计数器更新函数
  // ---------------------------------------------------------------------------
  def satUpdate(cur: UInt, increment: Bool): UInt = {
    val max_val = ((1 << counterBits) - 1).U
    val min_val = 0.U
    Mux(increment,
      // 如果为真则增加，但不超过最大值
      Mux(cur === max_val, max_val, cur + 1.U),
      // 如果为假则减少，但不低于最小值
      Mux(cur === min_val, min_val, cur - 1.U)
    )
  }
}
