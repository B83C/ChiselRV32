package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class Dispatcher_IO(implicit p:Parameters) extends Bundle {
  // with Rename
  val rename_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new RENAME_DISPATCH_uop()))) //来自rename单元的uop
  val ready = Output(Bool()) // 反馈给rename单元，显示Dispatch单元是否准备好接收指令
  // with ROB
  val rob_uop = Vec(p.CORE_WIDTH, Valid(new DISPATCH_ROB_uop())) //发往ROB的uop
  val rob_full = Input(Bool()) // ROB空标志(0表示非满，1表示满)
  val rob_head = Input(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB头部指针
  val rob_tail = Input(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB尾部指针，指向入队处
  val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷
  // with exu_issue
  val exuissue_uop = Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop())) //发往EXU的uop
  val exuissued_id = Vec(p.CORE_WIDTH, Flipped(Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W)))) //本周期EXU ISSUE queue发出指令对应的issue queue ID，用于更新iq freelist
  // with st_issue
  val stissue_uop = Vec(p.CORE_WIDTH, Valid(new DISPATCH_STISSUE_uop())) //发往Store Issue的uop
  val stissued_id = Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)).flip //本周期Store Issue queue发出指令对应的issue queue ID，用于更新issue queue freelist
  // with ld_issue
  val ldissue_uop = Vec(p.CORE_WIDTH, Valid(new DISPATCH_LDISSUE_uop())) //发往Load Issue的uop
  val ldissued_id = Valid(UInt(log2Ceil(p.LDISSUE_DEPTH).W)).flip //本周期Load Issue queue发出指令对应的issue queue ID，用于更新issue queue freelist
  // with sq
  val stq_full = Input(Bool()) // Store Queue空标志(0表示非满，1表示满)
  val stq_head = Input(UInt(log2Ceil(p.STQ_DEPTH).W)) //store queue头部指针
  val stq_tail = Input(UInt(log2Ceil(p.STQ_DEPTH).W)) //store queue尾部指针，指向入队处
  val dispatched_st = Output(Vec(p.CORE_WIDTH, Bool())) //本cycle派遣store指令的情况(00表示没有，01表示派遣一条，11表示派遣两条)，用于更新store queue（在lsu中）的tail（full标志位）
}

/*
  该模块负责将重命名单元发来的指令派遣到ROB，根据指令类型派遣到三个issue queue（分别为exu issue queue，store issue queue和load issue queue），如果是store指令，则还需要派遣到STQ（store queue）中。
  该模块通过和三个issue queue分别共同维护一个freelist（不是FIFO！），freelist到head和tail之间为可用的issue queue ID，dispatch单元选择head处的issue queue id进行派遣，issue queue发射指令后会将issue queue id
  反馈给dispatch单元，dispatch单元会将该id存入freelist的tail处。
*/

class Dispatcher(implicit p: Parameters) extends Module{
  val io = IO(new Dispatcher_IO)
}
