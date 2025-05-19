package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

import Utils._

class Dispatcher_IO(implicit p:Parameters) extends CustomBundle {
  // with Rename
  val dis_uop = Flipped(Decoupled(Vec(p.CORE_WIDTH, Flipped(Valid(new RENAME_DISPATCH_uop()))))) //来自rename单元的uop
  
  val rob_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new DISPATCH_ROB_uop()))) //发往ROB的uop
  
  val serialised_uop = Valid(new DISPATCH_EXUISSUE_uop()) // 只有在顺序执行模式下才有效
  
  //感觉可能csr采用到
  val rob_commitsignal = Flipped(Vec(p.CORE_WIDTH, Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷
  // with exu_issue
  val exu_issue_uop = Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop()))) //发往EXU的uop
  val exu_issued_id = Flipped(Vec(p.CORE_WIDTH, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W)))) //本周期EXU ISSUE queue发出指令对应的issue queue ID，用于更新iq freelist
  // with st_issue
  val st_issue_uop = Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_STISSUE_uop()))) //发往Store Issue的uop
  val st_issued_index = Flipped(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))//本周期Store Issue queue发出指令对应的issue queue ID，用于更新issue queue freelist
  // with ld_issue
  val ld_issue_uop = Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_LDISSUE_uop()))) //发往Load Issue的uop
  val ld_issued_index = Flipped(Valid(UInt(log2Ceil(p.LDISSUE_DEPTH).W)))//本周期Load Issue queue发出指令对应的issue queue ID，用于更新issue queue freelist
  // with sq
  val stq_full = Input(Bool()) // Store Queue空标志(0表示非满，1表示满)
  val stq_head = Input(UInt(log2Ceil(p.STQ_DEPTH).W)) //store queue头部指针
  val stq_tail = Input(UInt(log2Ceil(p.STQ_DEPTH).W)) //store queue尾部指针，指向入队处
  val st_cnt = Output(UInt(log2Ceil(p.CORE_WIDTH + 1).W)) //本cycle派遣store指令的数量

  val reset = Input(Bool())
}

// ooo_mode (Out of order) 表示乱序执行状态
// pre_ino_mode (In order) 表示顺序执行状态(且等待rob清空)
// ino_mode (In order) 表示顺序执行状态
// ooo_mode_r (Out of order) 表示顺序执行状态
object DispatchState extends ChiselEnum {
  val ooo_mode, pre_ino_mode, ino_mode = Value
}

// 该模块负责将重命名单元发来的指令派遣到ROB，根据指令类型派遣到三个issue queue（分别为exu issue queue，store issue queue和load issue queue），如果是store指令，则还需要派遣到STQ（store queue）中。
// 该模块通过和三个issue queue分别共同维护一个freelist（不是FIFO！），freelist到head和tail之间为可用的issue queue ID，dispatch单元选择head处的issue queue id进行派遣，issue queue发射指令后会将issue queue id
// 反馈给dispatch单元，dispatch单元会将该id存入freelist的tail处。
class DispatchUnit(implicit p: Parameters) extends CustomModule {
  val io = IO(new Dispatcher_IO)

  import DispatchState._
  val state = RegInit(ooo_mode)
  
  //函数定义见common/utils.scala
  val should_kill = reset || isMispredicted(io.rob_commitsignal(0))
  // TODO
  val rob_empty = false.B

  // Special Instruction - Serialised mode
  val is_csr = VecInit(io.rob_uop.bits.map(x => x.bits.instr_type === InstrType.CSR)).asUInt
  // val ooo_mask = 
  val serialise_mask = Reg(UInt(p.CORE_WIDTH.W)) // All 1's by default
  val still_has_csr = (serialise_mask & is_csr) =/= 0.U
  
  def sig_mask(value: UInt) : UInt = {
    val mid = PriorityEncoderOH(is_csr) - 1.U
    mid(p.CORE_WIDTH - 1 , 0)
  }
  val closest_mask = sig_mask(serialise_mask & is_csr) 
  val furthest_mask = ~closest_mask
  val ooo_mask = closest_mask & serialise_mask // Clamped within serialise mask to make sure ooo instructions before don't execute again

  val serialise_mask_mb = PriorityEncoderOH(serialise_mask) 
  val serialise_fire = serialise_mask_mb & is_csr 
  val serialise_fire_next = (serialise_mask_mb << 1) & is_csr 
  val selected_serialising_uop = Mux1H(serialise_fire.asBools, io.dis_uop.bits) //TODO Reverse because Mux1H is not Big endian whereas previous data are all big endian
  val serialised_uop_w = RegEnable(Wire(new DISPATCH_EXUISSUE_uop()), serialise_fire =/= 0.U)
  (serialised_uop_w: Data).waiveAll :<>= (selected_serialising_uop.bits: Data).waiveAll
  io.serialised_uop.bits := serialised_uop_w
  io.serialised_uop.valid := (rob_empty) && (serialise_fire =/= 0.U)
      
  //TODO: Hack
  val is_st = (VecInit(io.rob_uop.bits.map(_.bits.instr_type === InstrType.ST)).asUInt & ooo_mask)
  val is_ld = (VecInit(io.rob_uop.bits.map(_.bits.instr_type === InstrType.LD)).asUInt & ooo_mask)
  val is_ex = (~(is_st | is_ld)) & ooo_mask

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
     !io.stq_full &&
     exu_freelist.map(fl => fl.io.deq_request.valid).reduce(_ && _) &&
     ld_freelist.io.deq_request.valid &&
     st_freelist.io.deq_request.valid &&
     !still_has_csr
     
  exu_freelist.zip(io.exu_issued_id).zip(is_ex.asBools).foreach { case ((fl, id), v) =>
    fl.io.enq_request.bits := id.bits
    fl.io.enq_request.valid := id.valid
    fl.io.deq_request.ready := input_ready && v
  }
  Seq(ld_freelist).zip(Seq(io.ld_issued_index)).zip(is_ld.asBools).foreach { case ((fl, id), v) =>
    fl.io.enq_request.bits := id.bits
    fl.io.enq_request.valid := id.valid
    fl.io.deq_request.ready := input_ready && v
  }
  Seq(st_freelist).zip(Seq(io.st_issued_index)).zip(is_st.asBools).foreach { case ((fl, id), v) =>
    fl.io.enq_request.bits := id.bits
    fl.io.enq_request.valid := id.valid
    fl.io.deq_request.ready := input_ready && v
  }
  
  //TODO: Should be something else? Or is it sufficient
  io.dis_uop.ready := input_ready && !should_kill
  
  //Checks for every single uop, signals to update each output register
  val st_valid = is_st =/= 0.U
  val ld_valid = is_ld =/= 0.U
  val ex_valid = is_ex =/= 0.U

  io.st_cnt := Mux(should_kill, 0.U, PopCount(is_st.asBools))
   
  io.rob_uop.valid := RegNext(input_ready)
  io.rob_uop.bits := RegEnable(VecInit(io.dis_uop.bits.map{c =>
    val out = Wire(Valid(new DISPATCH_ROB_uop()))
    out.valid := c.valid
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out
  }), input_ready)
  
  io.exu_issue_uop.valid := RegNext(input_ready && ex_valid)
  io.exu_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_ex.asBools).zip(exu_freelist).map{case ((c, v), fl) =>
    val out = Wire(Valid(new DISPATCH_EXUISSUE_uop()))
    out.valid := c.valid &&  v
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out.bits.iq_index := fl.io.deq_request.bits
    out
  }), input_ready && ex_valid)
  
  io.ld_issue_uop.valid := RegNext(input_ready && ld_valid)
  io.ld_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_ld.asBools).zip(Seq(ld_freelist)).map{case ((c, v), fl) =>
    val out = Wire(Valid(new DISPATCH_LDISSUE_uop()))
    out.valid := c.valid &&  v
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out.bits.iq_index := fl.io.deq_request.bits
    out.bits.stq_tail := io.stq_tail //TODO: Check on its validity
    out
  }), input_ready && ld_valid)
  
  io.st_issue_uop.valid := RegNext(input_ready && st_valid)
  io.st_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_st.asBools).zip(Seq(st_freelist)).map{case ((c, v), fl) =>
    val out = Wire(Valid(new DISPATCH_STISSUE_uop()))

    out.valid := c.valid &&  v
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out.bits.iq_index := fl.io.deq_request.bits
    out.bits.stq_index := io.stq_head //TODO: Check on its validity
    out
  }), input_ready && st_valid)

  when(should_kill) {
    io.rob_uop.valid := 0.U
    io.exu_issue_uop.valid := 0.U
    io.ld_issue_uop.valid := 0.U
    io.st_issue_uop.valid := 0.U
    state := ooo_mode
  }.elsewhen(state === ooo_mode && still_has_csr) {
    serialise_mask := furthest_mask // Shift the mask to align the most significant bit with the first matching csr instruction within the previous serialise_mask window
    when(rob_empty) {
      state := ino_mode
    }.otherwise {
      state:= pre_ino_mode
    }
  }.elsewhen(state === pre_ino_mode && rob_empty) {
    state:= ino_mode
  }.elsewhen(state === ino_mode) {
    serialise_mask := serialise_mask << 1 
    when(serialise_fire_next =/= 0.U) {
      state := ino_mode
    }.otherwise {
      state := ooo_mode
    }
  }.elsewhen(state === ooo_mode && input_ready) {
    serialise_mask := ~0.U(p.CORE_WIDTH.W) // Reset back to all 1's
  }
}


