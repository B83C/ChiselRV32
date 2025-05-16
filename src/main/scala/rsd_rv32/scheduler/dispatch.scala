package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class Dispatcher_IO(implicit p:Parameters) extends CustomBundle {
  // with Rename
  val dis_uop = Flipped(Decoupled(Vec(p.CORE_WIDTH, Flipped(Valid(new RENAME_DISPATCH_uop()))))) //来自rename单元的uop
  
  val rob_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new DISPATCH_ROB_uop()))) //发往ROB的uop
  
  //感觉可能csr采用到
  val rob_commitsignal = Flipped(Vec(p.CORE_WIDTH, Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷

  //目前针对Exu处理
  val deq_request = Flipped(Decoupled(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.EXUISSUE_DEPTH).W))))
  val enq_request = Decoupled(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.EXUISSUE_DEPTH).W)))
  
  // with exu_issue
  val exu_issue_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop()))) //发往EXU的uop
  val exu_issued_id = Flipped(Vec(p.CORE_WIDTH, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W)))) //本周期EXU ISSUE queue发出指令对应的issue queue ID，用于更新iq freelist
  // with st_issue
  val st_issue_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new DISPATCH_STISSUE_uop()))) //发往Store Issue的uop
  val st_issued_index = Flipped(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))//本周期Store Issue queue发出指令对应的issue queue ID，用于更新issue queue freelist
  // with ld_issue
  val ld_issue_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new DISPATCH_LDISSUE_uop()))) //发往Load Issue的uop
  val ld_issued_index = Flipped(Valid(UInt(log2Ceil(p.LDISSUE_DEPTH).W)))//本周期Load Issue queue发出指令对应的issue queue ID，用于更新issue queue freelist
  // with sq
  val stq_full = Input(Bool()) // Store Queue空标志(0表示非满，1表示满)
  val stq_head = Input(UInt(log2Ceil(p.STQ_DEPTH).W)) //store queue头部指针
  val stq_tail = Input(UInt(log2Ceil(p.STQ_DEPTH).W)) //store queue尾部指针，指向入队处
  val st_cnt = Output(UInt(log2Ceil(p.CORE_WIDTH + 1).W)) //本cycle派遣store指令的数量
}

// TODO: freelist write, stq_head/tail, commit signal
// 该模块负责将重命名单元发来的指令派遣到ROB，根据指令类型派遣到三个issue queue（分别为exu issue queue，store issue queue和load issue queue），如果是store指令，则还需要派遣到STQ（store queue）中。
// 该模块通过和三个issue queue分别共同维护一个freelist（不是FIFO！），freelist到head和tail之间为可用的issue queue ID，dispatch单元选择head处的issue queue id进行派遣，issue queue发射指令后会将issue queue id
// 反馈给dispatch单元，dispatch单元会将该id存入freelist的tail处。
class DispatchUnit(implicit p: Parameters) extends CustomModule {
  val io = IO(new Dispatcher_IO)

  val input_ready = io.rob_uop.ready && io.ld_issue_uop.ready && io.st_issue_uop.ready && io.exu_issue_uop.ready && !io.stq_full
  
  //TODO
  io.dis_uop.ready := input_ready
  io.deq_request.ready := input_ready && io.dis_uop.valid
  
  //Checks for every single uop 
  val is_st = VecInit(io.rob_uop.bits.map(x => x.bits.instr_type === InstrType.ST))
  val is_ld = VecInit(io.rob_uop.bits.map(x => x.bits.instr_type === InstrType.LD))
  val is_ex = VecInit(is_st.zip(is_ld).map{case (x, y) => !x && !y})

  val st_valid = is_st.orR
  val ld_valid = is_ld.orR
  val ex_valid = is_ex.orR

  io.st_cnt := PopCount(is_st)
   
  io.rob_uop.valid := RegNext(input_ready)
  io.rob_uop.bits := RegEnable(VecInit(io.dis_uop.bits.map{c =>
    val out = Wire(Valid(new DISPATCH_ROB_uop()))
    out.valid := c.valid
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out
  }), input_ready)
  
  io.exu_issue_uop.valid := RegNext(input_ready && ex_valid)
  io.exu_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_ex).map{case (c, v) =>
    val out = Wire(Valid(new DISPATCH_EXUISSUE_uop()))
    out.valid := c.valid &&  v
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out
  }), input_ready && ex_valid)
  io.exu_issued_id := RegNext(io.deq_request.bits)
  
  io.ld_issue_uop.valid := RegNext(input_ready && ld_valid)
  io.ld_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_ld).map{case (c, v) =>
    val out = Wire(Valid(new DISPATCH_LDISSUE_uop()))
    out.valid := c.valid &&  v
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out
  }), input_ready && ld_valid)
  
  io.st_issue_uop.valid := RegNext(input_ready && st_valid)
  io.st_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_st).map{case (c, v) =>
    val out = Wire(Valid(new DISPATCH_STISSUE_uop()))
    out.valid := c.valid &&  v
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out
  }), input_ready && st_valid)
}


