package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.scheduler._
import java.awt.Robot

class BP_IO (implicit p: Parameters) extends CustomBundle {
  // from IF
  val instr_addr = Flipped(Valid(UInt(p.XLEN.W)))  // 当前PC值，双发射下指向第一条指令的PC

  // from IF
  // val ghr_restore = Flipped(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照
  val ghr_update = Flipped(Valid(Bool())) //作出预测时的全局历史寄存器快照

  // from BranchMask Unit
  val bu_update = Flipped(Valid(new BU_signals))
  val bu_commit = Flipped(Valid(new BU_signals))

  // to IF
  val predicted_next_pc = (UInt(p.XLEN.W))  // 预测的下个cycle取指的目标地址
  val btb_hits = Vec(p.CORE_WIDTH, Bool())
  val should_branch = Bool()
  val ghr = (UInt(p.GHR_WIDTH.W))  // 作出预测时的GHR快照

  // With ROB
  val rob_commitsignal = Flipped(ROB.CommitSignal) //来自于ROB的控制信号
  val rob_controlsignal = Flipped(new ROBControlSignal) //来自于ROB的控制信号
}

class BTBEntry(implicit val p: Parameters) extends Bundle {
    val valid = Bool()                // 有效位
    val target = UInt(p.XLEN.W)       // 跳转目标地址
    val isConditional = Bool()        // 是否条件分支
    val tag = UInt(21.W)              // PC 高位标签
}

class BranchPredictor(implicit p: Parameters) extends CustomModule {
  val io = IO(new BP_IO())

  val bimodeTableSize = 1024         // T/NT 表大小
  val choiceTableSize = 1024         // 选择器表大小
  val counterBits = 2                // 饱和计数器位宽
  val btbSize = 512                  // BTB 表大小
  val instBytes = 4                  // 指令字节宽度

  val should_flush = io.rob_controlsignal.shouldFlush

  //BHT
  val T_table = RegInit(VecInit(Seq.fill(bimodeTableSize)(2.U(counterBits.W))))
  val NT_table = RegInit(VecInit(Seq.fill(bimodeTableSize)(1.U(counterBits.W))))
  val choice_table = RegInit(VecInit(Seq.fill(choiceTableSize)(2.U(counterBits.W))))
  //BTB
  val btb = RegInit(VecInit(Seq.fill(btbSize)(0.U.asTypeOf(new BTBEntry))))
  //GHR
  val ghr = RegInit(0.U(p.GHR_WIDTH.W))
  io.ghr := ghr

  //common methods
  def get_btbIndex(pc: UInt): UInt = pc(log2Ceil(btbSize) + log2Ceil(instBytes) - 1, log2Ceil(instBytes))
  def get_btbTag(pc: UInt): UInt = pc(p.XLEN-1, log2Ceil(instBytes) + log2Ceil(btbSize))
  def get_histIndex(pc: UInt, ghr: UInt): UInt = (pc(p.XLEN-1, log2Ceil(instBytes)) ^ ghr.pad(p.XLEN - log2Ceil(instBytes)))(log2Ceil(bimodeTableSize)-1, 0)
  def get_choiceIndex(pc: UInt): UInt = pc(log2Ceil(choiceTableSize) - 1 + log2Ceil(instBytes), log2Ceil(instBytes))

  val pc_ready = io.instr_addr.valid
  // TODO
  val pc_aligned = io.instr_addr.bits & ~((p.CORE_WIDTH << 2).U(p.XLEN.W) - 1.U)         //对齐后的当前PC
  val packet_first_valid_slot = io.instr_addr.bits(log2Ceil(p.CORE_WIDTH - 1) + 2 , 2)
  val offset_mask = VecInit((~0.U(p.CORE_WIDTH.W) << packet_first_valid_slot)(p.CORE_WIDTH - 1, 0).asBools)

  val pc = VecInit((0 until p.CORE_WIDTH).map(i => pc_aligned + (i << 2).U))

  // TODO
  val btb_entries = pc.map{case pc => btb(get_btbIndex(pc))}
  val btb_hits = btb_entries.zip(pc).zip(offset_mask).map{case ((be, pc), valid) => valid && be.valid && be.tag === get_btbTag(pc)}

  // TODO 
  val btb_hit = VecInit(btb_hits).asUInt =/= 0.U
  val first_hit = PriorityMux(btb_hits.zip(btb_entries))
  val first_hit_pc = PriorityMux(btb_hits.zip(pc))

  io.should_branch := false.B 
  io.predicted_next_pc := 0.U
  io.btb_hits := btb_hits 
  when(pc_ready && btb_hit) {
    val choice = choice_table(get_choiceIndex(first_hit_pc))(1)
    val taken = Mux(first_hit.isConditional,
      Mux(choice, T_table(get_histIndex(first_hit_pc, ghr))(1), NT_table(get_histIndex(first_hit_pc, ghr))(1)), true.B)
    io.should_branch := taken 
    io.predicted_next_pc := Mux(taken, first_hit.target, 0.U)
  }

  //bht and btb update logic
  def counter_update(counter: UInt, branch_taken: Bool): UInt = {
    val result = WireDefault(counter)
    when(branch_taken){
      result := Mux(counter === 3.U, 3.U, counter + 1.U)
    }.otherwise{
      result := Mux(counter === 0.U, 0.U, counter - 1.U)
    }
    result
  }

  val bu_signal = io.bu_commit.bits
  // 错误预测的情况

  val branches = io.rob_commitsignal.bits.map(e =>  e.valid && e.mispred)
  val has_got_branches = VecInit(branches).asUInt =/= 0.U && io.rob_commitsignal.valid
  val first_branch = PriorityMux(branches, io.rob_commitsignal.bits)

  when(has_got_branches) {
    ghr := Cat(first_branch.ghr, first_branch.branch_taken)
  }.elsewhen(io.ghr_update.fire) {
    ghr := Cat(ghr, io.ghr_update.bits)
  }

  when(has_got_branches) {
    val choice = choice_table(get_choiceIndex(first_branch.instr_addr))
    val direction = first_branch.branch_taken
    choice := counter_update(choice, direction)

    val table_index = get_histIndex(first_branch.instr_addr, first_branch.ghr)
    // TODO 
    when(choice(1)) {
      T_table(table_index) := counter_update(T_table(table_index), direction)
    }.otherwise {
      NT_table(table_index) := counter_update(NT_table(table_index), direction)
    }

    val btb_entry = btb(get_btbIndex(first_branch.instr_addr))
    btb_entry.target := first_branch.target_PC
    // TODO
    btb_entry.isConditional := first_branch.is_conditional // If it writes back then it definitely is jump
    btb_entry.valid := true.B
    btb_entry.tag := get_btbTag(first_branch.instr_addr)
  }

  // when(io.rob_commitsignal.valid) {
  //   commit_signal.foreach{cs => {
  //     when(cs.valid && cs.mispred) {
  //       val choice = choice_table(get_choiceIndex(cs.instr_addr))
  //       val direction = cs.branch_taken
  //       choice := counter_update(choice, direction)

  //       val table_index = get_histIndex(cs.instr_addr, cs.GHR)
  //       // TODO 
  //       when(choice(1)) {
  //         T_table(table_index) := counter_update(T_table(table_index), direction)
  //       }.otherwise {
  //         NT_table(table_index) := counter_update(NT_table(table_index), direction)
  //       }

  //       val btb_entry = btb(get_btbIndex(cs.instr_addr))
  //       btb_entry.target := cs.target_PC
  //       // TODO
  //       btb_entry.isConditional := !cs.pdst.valid // If it writes back then it definitely is jump
  //       btb_entry.valid := true.B
  //       btb_entry.tag := get_btbTag(cs.instr_addr)
  //     }
  //   }}

  //   // This can be evaded if we change our way of handling branches
  //   // GHR Update logic: Since only 1 branch can be taken at a time, and only branches that are successfully predicted as not taken reside in the fetch packet. Therefore, when updating the ghr, there should only be one 1 bit and not two or more. Moreover, any other instructions after the branch is invalid, so we should not consider them and only consider the first taken one
  //   val branch_cnt_til_taken = PopCount(commit_signal.map(x => x.valid && x.is_branch))
  //   // TODO
  //   val last_branch_taken = (commit_signal.map(x => x.valid && x.branch_taken)).reduce(_ || _)
  //   ghr := ghr << branch_cnt_til_taken | last_branch_taken
  // }

  // when(io.rob_controlsignal.valid && io.rob_controlsignal.bits.vGHR.valid) {
  //   ghr := io.rob_controlsignal.bits.vGHR.bits
  // }.elsewhen(io.rob_commitsignal.valid) {
  //   ghr := io.
  // }
}
