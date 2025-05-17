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

// TODO: CSR
// 该模块负责将重命名单元发来的指令派遣到ROB，根据指令类型派遣到三个issue queue（分别为exu issue queue，store issue queue和load issue queue），如果是store指令，则还需要派遣到STQ（store queue）中。
// 该模块通过和三个issue queue分别共同维护一个freelist（不是FIFO！），freelist到head和tail之间为可用的issue queue ID，dispatch单元选择head处的issue queue id进行派遣，issue queue发射指令后会将issue queue id
// 反馈给dispatch单元，dispatch单元会将该id存入freelist的tail处。
class DispatchUnit(implicit p: Parameters) extends CustomModule {
  val io = IO(new Dispatcher_IO)

  val is_st = VecInit(io.rob_uop.bits.map(x => x.bits.instr_type === InstrType.ST))
  val is_ld = VecInit(io.rob_uop.bits.map(x => x.bits.instr_type === InstrType.LD))
  val is_ex = VecInit(is_st.zip(is_ld).map{case (x, y) => !x && !y})

  val exu_issue_depth_bits = log2Ceil(p.EXUISSUE_DEPTH)
  val ld_issue_depth_bits = log2Ceil(p.LDISSUE_DEPTH)
  val st_issue_depth_bits = log2Ceil(p.STISSUE_DEPTH)
  //为了解决单周期iq条目释放数量的不确定行，对不同通道设立一个独立的freelist，使两者地址域不交叠
  val exu_freelist = (0 until p.CORE_WIDTH)
    .map{idx => Module(new FreeList(
      UInt(exu_issue_depth_bits.W),
      p.EXUISSUE_DEPTH / p.CORE_WIDTH,
      i => (i * p.CORE_WIDTH + idx).U(exu_issue_depth_bits.W)
    ))}
  
  val ld_freelist = Module(new FreeList(
    UInt(ld_issue_depth_bits.W),
    p.LDISSUE_DEPTH,
    i => i.U(ld_issue_depth_bits.W)))
  val st_freelist = Module(new FreeList(
    UInt(st_issue_depth_bits.W),
    p.STISSUE_DEPTH,
    i => i.U(st_issue_depth_bits.W)))

  val input_ready = io.rob_uop.ready &&
     io.ld_issue_uop.ready &&
     io.st_issue_uop.ready &&
     io.exu_issue_uop.ready &&
     !io.stq_full &&
     exu_freelist.map(fl => fl.io.deq_request.valid).reduce(_ && _) &&
     ld_freelist.io.deq_request.valid &&
     st_freelist.io.deq_request.valid
  
  exu_freelist.zip(io.exu_issued_id).zip(is_ex).foreach { case ((fl, id), v) =>
    fl.io.enq_request.bits := id.bits
    fl.io.enq_request.valid := id.valid
    fl.io.deq_request.ready := input_ready && v
  }
  Seq(ld_freelist).zip(Seq(io.ld_issued_index)).zip(is_ld).foreach { case ((fl, id), v) =>
    fl.io.enq_request.bits := id.bits
    fl.io.enq_request.valid := id.valid
    fl.io.deq_request.ready := input_ready && v
  }
  Seq(st_freelist).zip(Seq(io.st_issued_index)).zip(is_st).foreach { case ((fl, id), v) =>
    fl.io.enq_request.bits := id.bits
    fl.io.enq_request.valid := id.valid
    fl.io.deq_request.ready := input_ready && v
  }
  
  //TODO: Should be something else? Or is it sufficient
  io.dis_uop.ready := input_ready
  
  //Checks for every single uop, signals to update each output register
  val st_valid = is_st.reduce(_ || _)
  val ld_valid = is_ld.reduce(_ || _)
  val ex_valid = is_ex.reduce(_ || _)

  io.st_cnt := PopCount(is_st)
   
  io.rob_uop.valid := RegNext(input_ready)
  io.rob_uop.bits := RegEnable(VecInit(io.dis_uop.bits.map{c =>
    val out = Wire(Valid(new DISPATCH_ROB_uop()))
    out.valid := c.valid
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out
  }), input_ready)
  
  io.exu_issue_uop.valid := RegNext(input_ready && ex_valid)
  io.exu_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_ex).zip(exu_freelist).map{case ((c, v), fl) =>
    val out = Wire(Valid(new DISPATCH_EXUISSUE_uop()))
    out.valid := c.valid &&  v
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out.bits.iq_index := fl.io.deq_request.bits
    out
  }), input_ready && ex_valid)
  
  io.ld_issue_uop.valid := RegNext(input_ready && ld_valid)
  io.ld_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_ld).zip(Seq(ld_freelist)).map{case ((c, v), fl) =>
    val out = Wire(Valid(new DISPATCH_LDISSUE_uop()))
    out.valid := c.valid &&  v
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out.bits.iq_index := fl.io.deq_request.bits
    out.bits.stq_tail := io.stq_tail //TODO: Check on its validity
    out
  }), input_ready && ld_valid)
  
  io.st_issue_uop.valid := RegNext(input_ready && st_valid)
  io.st_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_st).zip(Seq(st_freelist)).map{case ((c, v), fl) =>
    val out = Wire(Valid(new DISPATCH_STISSUE_uop()))
    out.valid := c.valid &&  v
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out.bits.iq_index := fl.io.deq_request.bits
    out.bits.stq_index := io.stq_head //TODO: Check on its validity
    out
  }), input_ready && st_valid)
}


