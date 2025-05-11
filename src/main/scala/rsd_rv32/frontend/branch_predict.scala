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
    //with IF
    val instr_addr = Input(UInt(p.XLEN.W)) //当前PC值，用于访问BTB获得跳转目标地址，以及访问BHT获得跳转预测结果
    val target_PC = Output(UInt(p.XLEN.W)) //预测的下个cycle取指的目标地址
    val btb_hit = Output(Vec(p.CORE_WIDTH, Bool())) //1代表hit，0相反；将指令包中命中BTB的最年长的置为1，其余为0
    val branch_pred = Output(Bool()) //条件分支指令的BHT的预测结果；1代表跳转，0相反；非条件分支置1
    val GHR = Output(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照，随流水级传递，在ROB退休分支指令时更新BHT

    //with ROB
    val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))) //ROB提交时的广播信号，从中识别出分支指令更新BHT和BTB
}

/*
本模块采用bi-mode分支预测算法，采用BTB + BHT结合的方式工作。主要维护：1、BTB 2、BHT（内含T表和NT表，选择器）
    取指单元送来当前取指地址PC，以PC和PC+4（取指宽度为2）索引BTB，若为条件分支指令（该信息存储在BTB中），则用PC与GHR的索引函数索引BHT，得到预测结果，并更新GHR（此时的GHR为推测执行的GHR）。
若为无条件分支指令，则直接将BTB中索引到的目标地址发送给取指单元即可。
    branch predictor单元通过ROB广播的信号来更新BTB，BHT以及修正GHR(如果发生误预测)。
*/

class BranchPredictorUnit(implicit p: Parameters) extends Module {
    // Bi-Mode 参数
    val bimodeTableSize = 1024               // T/NT 表大小
    val choiceTableSize = 1024               // 选择器表大小
    val counterBits     = 2                  // 饱和计数器位宽
    val btbSize         = 512                // BTB 表大小
    val instBytes       = 4                  // 指令字节宽度
    val io = IO(new BP_IO())
// ---------------------------------------------------------------------------
// 1. BTB 部分
// ---------------------------------------------------------------------------
// BTB 条目定义
  class BTBEntry extends Bundle {
    val valid         = Bool()              // 有效位
    val target        = UInt(p.XLEN.W)      // 跳转目标地址
    val isConditional = Bool()              // 是否条件分支
    val tag           = UInt(20.W)          // PC 高位标签 - 修复: 给定固定宽度
  }
  
  // 全表存储
  val btb = Mem(btbSize, new BTBEntry)

  // 从 PC 生成索引和标签
  def btbIndex(pc: UInt): UInt = pc(log2Ceil(btbSize) + log2Ceil(instBytes) - 1, log2Ceil(instBytes))
  def btbTag(pc: UInt): UInt   = pc(p.XLEN-1, log2Ceil(instBytes) + log2Ceil(btbSize))

  // 查表
  val btbIdx = btbIndex(io.instr_addr)
  val btbTag = btbTag(io.instr_addr)
  val rawEntry = btb.read(btbIdx)
  val btbHit = rawEntry.valid && rawEntry.tag === btbTag

  // ---------------------------------------------------------------------------
  // 2. Bi-Mode BHT 部分
  // ---------------------------------------------------------------------------
  // 全局历史寄存器 GHR
  val ghr = RegInit(0.U(p.GHR_WIDTH.W))
  io.GHR := ghr  // 不经过RegNext直接连接输出
  
  // T/NT 饱和计数器表
  val bimodeT  = Mem(bimodeTableSize, UInt(counterBits.W))
  val bimodeNT = Mem(bimodeTableSize, UInt(counterBits.W))
  // 选择器表
  val choice = Mem(choiceTableSize, UInt(counterBits.W))

  // 索引函数
  val histIdx = (io.instr_addr ^ ghr)(log2Ceil(bimodeTableSize)-1, 0)
  val tagIdx  = io.instr_addr(log2Ceil(choiceTableSize)-1, 0)

  // 读取计数器
  val cntT  = bimodeT.read(histIdx)
  val cntNT = bimodeNT.read(histIdx)
  val sel   = choice.read(tagIdx)

  // Bi-Mode 预测：sel 高位决定用哪表，取表中 MSB
  val useNT   = sel(counterBits-1)
  val predBit = Mux(useNT, cntNT(counterBits-1), cntT(counterBits-1))

  // ---------------------------------------------------------------------------
  // 3. 最终预测逻辑
  // ---------------------------------------------------------------------------
  // 计算最终预测
  val finalPred = Wire(Bool())
  finalPred := Mux(btbHit && !rawEntry.isConditional, true.B,  // 无条件跳转
                Mux(btbHit && rawEntry.isConditional, predBit, false.B))  // 条件分支或不命中

  // BTB hit 向量：实现优先级编码，最年轻的命中指令被标记
  val hitVec = Wire(Vec(p.CORE_WIDTH, Bool()))
  for (i <- 0 until p.CORE_WIDTH) {
    if (i == 0) {
      hitVec(i) := btbHit
    } else {
      hitVec(i) := btbHit && !hitVec.asUInt()(i-1, 0).orR
    }
  }

  // 避免组合环路，使用寄存器暂存
  val nextPC = Wire(UInt(p.XLEN.W))
  nextPC := Mux(finalPred, rawEntry.target, io.instr_addr + instBytes.U)

  // 暂存寄存器
  val nextPCReg = RegNext(nextPC)
  val hitVecReg = RegNext(hitVec)
  val finalPredReg = RegNext(finalPred)

  // IF 输出
  io.btb_hit := hitVecReg
  io.target_PC := nextPCReg
  io.branch_pred := finalPredReg

  // ---------------------------------------------------------------------------
  // 4. 更新逻辑：ROB 提交时写回 BTB/BHT
  // ---------------------------------------------------------------------------
  for (i <- 0 until p.CORE_WIDTH) {
    when (io.rob_commitsignal(i).valid) {
      val rc    = io.rob_commitsignal(i).bits
      val pc    = rc.instr_addr
      //val taken = rc.taken
      when (rc.rob_type === ROBType.Branch) { // 如果是分支指令
        val branch_data = rc.as_Branch         // 转换负载为 ROB_Branch 类型
        val taken = branch_data.branch_direction // 实际跳转结果
        val ghr_snapshot = branch_data.GHR    // 正确访问 GHR
      }
      val target = MuxCase(0.U, Seq(
        (rc.rob_type === ROBType.Branch) -> rc.as_Branch.target_PC,
        (rc.rob_type === ROBType.Jump)   -> rc.as_Jump.target_PC
      ))
     // BTB 写入或更新：条件分支或跳转都写入
      val idx   = btbIndex(pc)
      val newE  = Wire(new BTBEntry)
      newE.valid         := true.B  // 修复: 应始终有效，由isBranch或taken决定是否写入
      newE.target        := target
      newE.isConditional := rc.rob_type === ROBType.Branch
      newE.tag           := btbTag(pc)
      
      // 只有当是分支或确实发生跳转时才更新BTB
      when (rc.rob_type === ROBType.Branch || taken) {
        btb.write(idx, newE)
      }

      // BHT 更新：使用提交时的 GHR 快照
      val updHist = ghr_snapshot
      val hIdx    = (pc ^ updHist)(log2Ceil(bimodeTableSize)-1, 0)
      val tIdx    = pc(log2Ceil(choiceTableSize)-1, 0)
      
      // 只更新条件分支的预测表
      when (rc.rob_type === ROBType.Branch) {
        // 读取当前计数器值
        val tEntry  = bimodeT.read(hIdx)
        val ntEntry = bimodeNT.read(hIdx)
        val oldSel  = choice.read(tIdx)
        
        // 判断当前预测表
        val useT = !oldSel(counterBits-1)
        
        // 更新各表饱和计数器
        bimodeT.write(hIdx, satUpdate(tEntry, taken))
        bimodeNT.write(hIdx, satUpdate(ntEntry, taken))
        
        // 更新选择器：当两种预测不同时
        val tPred = tEntry(counterBits-1).asBool
        val ntPred = ntEntry(counterBits-1).asBool
        
        when (tPred =/= ntPred) {
          val newSel = Mux(taken === tPred, 
                         satUpdate(oldSel, false.B),  // 使用T表更准确，向0移动
                         satUpdate(oldSel, true.B))   // 使用NT表更准确，向1移动
          choice.write(tIdx, newSel)
        }

        // 更新 GHR - 只有条件分支才更新
        ghr := Cat(ghr(p.GHR_WIDTH-2, 0), taken.asUInt)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 饱和计数器更新函数
  // ---------------------------------------------------------------------------
  def satUpdate(cur: UInt, taken: Bool): UInt = {
    Mux(taken,
      Mux(cur === ((1 << counterBits) - 1).U, cur, cur + 1.U),  // 饱和计数器最大值
      Mux(cur === 0.U, cur, cur - 1.U))
  }
  
  // 初始化内存
  if (System.getProperty("RANDOMIZE_MEM_INIT") != null) {
    // T表默认初始化为2'b01 (弱取), NT表默认初始化为2'b01 (弱不取)
    for (i <- 0 until bimodeTableSize) {
      bimodeT.write(i.U, 1.U(counterBits.W))
      bimodeNT.write(i.U, 2.U(counterBits.W))  // 修复: NT表应初始化为2'b10 (弱不取)
    }
    // 选择器默认为1 (弱选择NT表)
    for (i <- 0 until choiceTableSize) {
      choice.write(i.U, 1.U(counterBits.W))
    }
  }
}
