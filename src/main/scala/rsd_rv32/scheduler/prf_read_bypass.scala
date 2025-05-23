package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

import Utils._

class prf_read_bypass_io(implicit p:Parameters) extends CustomBundle {
  // with Rename
  val dis_uop = Flipped(Decoupled(Vec(p.CORE_WIDTH, Flipped(Valid(new RENAME_DISPATCH_uop()))))) //来自rename单元的uop
  
  val rob_uop = Vec(p.CORE_WIDTH, Valid(new DISPATCH_ROB_uop())) //发往ROB的uop
  
  val rob_full = Flipped(Bool())
  val rob_head = Flipped(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB头指针
  val rob_tail = Flipped(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB尾指针
  
  val serialised_uop = Valid(new DISPATCH_EXUISSUE_uop()) // 只有在顺序执行模式下才有效
  
  //感觉可能csr采用到
  val rob_commitsignal = Flipped(Vec(p.CORE_WIDTH, Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷
  // with exu_issue
  val exu_issue_uop = Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop()))) //发往EXU的uop
  val exu_issued_index = Flipped(Vec(p.CORE_WIDTH, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W)))) //本周期EXU ISSUE queue发出指令对应的issue queue ID，用于更新iq freelist
  // with st_issue
  val st_issue_uop = Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_STISSUE_uop()))) //发往Store Issue的uop
  val st_issued_index = Flipped(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))//本周期Store Issue queue发出指令对应的issue queue ID，用于更新issue queue freelist
  // with ld_issue
  val ld_issue_uop = Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_LDISSUE_uop()))) //发往Load Issue的uop
  val ld_issued_index = Flipped(Valid(UInt(log2Ceil(p.LDISSUE_DEPTH).W)))//本周期Load Issue queue发出指令对应的issue queue ID，用于更新issue queue freelist
  // with sq
  val stq_full = Flipped(Bool()) // Store Queue空标志(0表示非满，1表示满)
  val stq_head = Flipped(UInt(log2Ceil(p.STQ_DEPTH).W)) //store queue头部指针
  val stq_tail = Flipped(UInt(log2Ceil(p.STQ_DEPTH).W)) //store queue尾部指针，指向入队处
  val st_cnt = (UInt(log2Ceil(p.CORE_WIDTH + 1).W)) //本cycle派遣store指令的数量
}

// 该模块负责将重命名单元发来的指令派遣到ROB，根据指令类型派遣到三个issue queue（分别为exu issue queue，store issue queue和load issue queue），如果是store指令，则还需要派遣到STQ（store queue）中。
// 该模块通过和三个issue queue分别共同维护一个freelist（不是FIFO！），freelist到head和tail之间为可用的issue queue ID，dispatch单元选择head处的issue queue id进行派遣，issue queue发射指令后会将issue queue id
// 反馈给dispatch单元，dispatch单元会将该id存入freelist的tail处。
class prf_read_bypass(implicit p: Parameters) extends CustomModule {

  val io = IO(new prf_read_bypass_io)
  // Debugging
  import chisel3.experimental.BundleLiterals._
}


