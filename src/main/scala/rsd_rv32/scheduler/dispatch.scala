package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

import Utils._

class Dispatcher_IO(implicit p:Parameters) extends CustomBundle {
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
  val st_cnt = Output(UInt(log2Ceil(p.CORE_WIDTH + 1).W)) //本cycle派遣store指令的数量
}

// ooo_mode (Out of order) 表示乱序执行状态
// pre_ino_mode (In order) 表示顺序执行状态(且等待rob清空)
// ino_mode (In order) 表示顺序执行状态
// post_ino_mode (In order) 表示顺序执行状态，并且等待执行完毕，目前不等待，只延迟一周期
object DispatchState extends ChiselEnum {
  val ooo_mode, pre_ino_mode, ino_mode, post_ino_mode = Value
}

// 该模块负责将重命名单元发来的指令派遣到ROB，根据指令类型派遣到三个issue queue（分别为exu issue queue，store issue queue和load issue queue），如果是store指令，则还需要派遣到STQ（store queue）中。
// 该模块通过和三个issue queue分别共同维护一个freelist（不是FIFO！），freelist到head和tail之间为可用的issue queue ID，dispatch单元选择head处的issue queue id进行派遣，issue queue发射指令后会将issue queue id
// 反馈给dispatch单元，dispatch单元会将该id存入freelist的tail处。
class DispatchUnit(implicit p: Parameters) extends CustomModule {

  implicit val debug_print_enabled: Bool = true.B
  
  val io = IO(new Dispatcher_IO)

  import DispatchState._
  val state = RegInit(ooo_mode)
  
  //函数定义见common/utils.scala
  val should_kill = reset.asBool || isMispredicted(io.rob_commitsignal(0))
  val rob_empty = (io.rob_head === io.rob_tail) && !io.rob_full
  // 对于wrap around的情况不影响: 修改rob可简化（不需要大改）
  val rob_tail_advance = io.rob_tail + p.CORE_WIDTH.U
  val wrapped_around = rob_tail_advance < io.rob_tail
  val rob_ready = (Mux(io.rob_head > io.rob_tail || wrapped_around, io.rob_head >= rob_tail_advance, rob_tail_advance >= io.rob_head)) && !io.rob_full

  // Special Instruction - Serialised mode
  val is_csr = VecInit(io.rob_uop.map(x => x.bits.instr_type.isOneOf(InstrType.CSR))).asUInt
  // val ooo_mask = 
  val serialise_mask = Reg(UInt(p.CORE_WIDTH.W)) // All 1's by default
  val still_has_csr = (serialise_mask & is_csr) =/= 0.U 
  serialise_mask := ~0.U(p.CORE_WIDTH.W)
  
  def sig_mask(value: UInt) : UInt = {
    val mid = PriorityEncoderOH(value) - 1.U
    mid(p.CORE_WIDTH - 1 , 0)
  }

  val clamped_is_csr = Cat(1.U, serialise_mask & is_csr) // 1.U is padded at the furthest, note this is little endian
  val closest_mask = sig_mask(clamped_is_csr)  // Generates 1's mask that appear right before the next csr instruction, the clamped_is_csr is clamped to make sure when csr is not detected till the end of the packet, it is handled correctly (that closest_mask should be 1's for all the left non-csr instructions)
  val furthest_mask = ~closest_mask
  val ooo_mask = closest_mask & serialise_mask // Clamped within serialise mask to make sure ooo instructions dispatched before don't appear again

  dbg(cf"state : ${state}\n")
  dbg(cf"ooo_mask ${serialise_mask}%b ${is_csr}%b ${ooo_mask}%b ${closest_mask}%b ${furthest_mask}%b ${clamped_is_csr}%b\n")

  val serialise_mask_mb = PriorityEncoderOH(serialise_mask) 
  val serialise_fire = serialise_mask_mb & is_csr 
  val serialise_fire_next = (serialise_mask_mb << 1) & is_csr 
  
  val selected_serialising_uop = Mux1H(serialise_fire.asBools, io.dis_uop.bits) 
  
  val dispatch_serialised = serialise_fire =/= 0.U && state === ino_mode
  val serialised_uop_w = Reg(new DISPATCH_EXUISSUE_uop())
  when(dispatch_serialised) {
    (serialised_uop_w: Data).waiveAll :<>= (selected_serialising_uop.bits: Data).waiveAll
  } 
  io.serialised_uop.bits := serialised_uop_w
  io.serialised_uop.valid := RegNext(dispatch_serialised)
      
  val all_valid = io.dis_uop.valid

  import InstrType._
  //TODO: Hack
  val is_st = Mux(state === ooo_mode, (VecInit(io.dis_uop.bits.map(x => (x.bits.instr_type.isOneOf(ST)) && x.valid)).asUInt & ooo_mask), 0.U)
  val is_ld = Mux(state === ooo_mode, (VecInit(io.dis_uop.bits.map(x => (x.bits.instr_type.isOneOf(LD)) && x.valid)).asUInt & ooo_mask), 0.U)
  val is_ex = Mux(state === ooo_mode, (VecInit(io.dis_uop.bits.map(x => ~(x.bits.instr_type.isOneOf(ST, LD)) && x.valid)).asUInt & ooo_mask), 0.U)

  dbg(cf"st ${is_st}%b ${is_ld}%b ${is_ex}%b\n")

  val exu_issue_depth_bits = log2Ceil(p.EXUISSUE_DEPTH)
  val ld_issue_depth_bits = log2Ceil(p.LDISSUE_DEPTH)
  val st_issue_depth_bits = log2Ceil(p.STISSUE_DEPTH)
  //为了解决单周期iq条目释放数量的不确定行，对不同通道设立一个独立的freelist，使两者地址域不交叠
  // Write back length is determined by issue width, not entirely core_width
  val exu_freelist = Module(new FreeListCam(p.EXUISSUE_DEPTH, p.CORE_WIDTH, p.CORE_WIDTH)) 
  val ld_freelist = Module(new FreeListCam(p.LDISSUE_DEPTH, p.CORE_WIDTH, 1)) 
  val st_freelist = Module(new FreeListCam(p.STISSUE_DEPTH, p.CORE_WIDTH, 1)) 

  exu_freelist.io.checkpoint := false.B
  exu_freelist.io.restore := false.B
  ld_freelist.io.checkpoint := false.B
  ld_freelist.io.restore := false.B
  st_freelist.io.checkpoint := false.B
  st_freelist.io.restore := false.B
  
  val input_ready = Wire(Bool())
  
  exu_freelist.io.enq_request := io.exu_issued_index
  ld_freelist.io.enq_request := VecInit(io.ld_issued_index)
  st_freelist.io.enq_request := VecInit(io.st_issued_index)
  
  (exu_freelist.io.deq_request zip is_ex.asBools).foreach{ case(dq, v) =>
    dq.ready := v  
  }  
  (ld_freelist.io.deq_request  zip is_ld.asBools).foreach{ case(dq, v) =>
    dq.ready := v  
  } 
  (st_freelist.io.deq_request  zip is_st.asBools).foreach{ case(dq, v) =>
    dq.ready := v  
  } 
  
  // Ready fields should be ready when it is not valid, or when it is a valid instruction and freelist is ready
  val ex_ready = (exu_freelist.io.deq_request zip is_ex.asBools).map{ case(dq, v) =>
    dbg(cf" exu ${dq.bits} ${dq.valid} || ${v} \n")
    (dq.valid) || !v
  }  
  val ld_ready = (ld_freelist.io.deq_request  zip is_ld.asBools).map{ case(dq, v) =>
    dbg(cf" ld ${dq.bits} ${dq.valid} || ${v} \n")
    (dq.valid) || !v
  } 
  val st_ready = (st_freelist.io.deq_request  zip is_st.asBools).map{ case(dq, v) =>
    dbg(cf" st ${dq.bits} ${dq.valid} || ${v} \n")
    (dq.valid) || !v
  } 

  exu_freelist.io.squeeze := should_kill
  ld_freelist.io.squeeze := should_kill
  st_freelist.io.squeeze := should_kill

  val ex_ready_R = ex_ready.reduce(_ && _)
  val ld_ready_R = ld_ready.reduce(_ && _)
  val st_ready_R = st_ready.reduce(_ && _)

  input_ready := rob_ready &&
     !io.stq_full &&
     ex_ready_R &&
     ld_ready_R &&
     st_ready_R &&
     !still_has_csr &&
     state === ooo_mode

  dbg(cf"${input_ready} ${rob_ready} ${io.stq_full} ${ex_ready_R} ${ld_ready_R} ${st_ready_R} ${still_has_csr} \n")
  
  //TODO: Should be something else? Or is it sufficient
  io.dis_uop.ready := input_ready && !should_kill 
  
  //Checks for every single uop, signals to update each output register
  val st_valid = is_st =/= 0.U
  val ld_valid = is_ld =/= 0.U
  val ex_valid = is_ex =/= 0.U

  io.st_cnt := RegNext(PopCount(is_st.asBools))
   
  val rob_uop_valid = RegNext(input_ready)
  io.rob_uop := RegEnable(VecInit(io.dis_uop.bits.map{c =>
    val out = Wire(Valid(new DISPATCH_ROB_uop()))
    out.valid := c.valid && input_ready
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    // TODO: bug, maybe instr should not be truncated
    out.bits.rd := c.bits.instr(4, 0)
    out
  }), input_ready)

  // Can be omitted if we store them all simutaneously
  val rob_indicies = (0 until p.CORE_WIDTH).map{ i => io.rob_tail + i.U}
  
  io.exu_issue_uop.valid := RegNext(input_ready && ex_valid)
  io.exu_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_ex.asBools).zip(exu_freelist.io.deq_request).zip(rob_indicies).map{case (((c, v), fl), rob_ind) =>
    val out = Wire(Valid(new DISPATCH_EXUISSUE_uop()))
    out.valid := c.valid && v && fl.valid
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    //TODO: This is not that appropriate
    out.bits.iq_index := fl.bits
    out.bits.rob_index := rob_ind
    out
  }), input_ready && ex_valid)
  
  io.ld_issue_uop.valid := RegNext(input_ready && ld_valid)
  io.ld_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_ld.asBools).zip(ld_freelist.io.deq_request).zip(rob_indicies).map{case (((c, v), fl), rob_ind) =>
    val out = Wire(Valid(new DISPATCH_LDISSUE_uop()))
    out.valid := c.valid &&  v && fl.valid
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out.bits.iq_index := fl.bits
    out.bits.stq_tail := io.stq_tail //TODO: Check on its validity
    out.bits.rob_index := rob_ind
    out
  }), input_ready && ld_valid)
  
  io.st_issue_uop.valid := RegNext(input_ready && st_valid)
  io.st_issue_uop.bits := RegEnable(VecInit(io.dis_uop.bits.zip(is_st.asBools).zip(st_freelist.io.deq_request).zip(rob_indicies).map{case (((c, v), fl), rob_ind) =>
    val out = Wire(Valid(new DISPATCH_STISSUE_uop()))

    out.valid := c.valid &&  v && fl.valid
    (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
    out.bits.iq_index := fl.bits
    out.bits.rob_index := rob_ind
    out.bits.stq_index := io.stq_head //TODO: Check on its validity
    out
  }), input_ready && st_valid)

  when(should_kill) {
    io.exu_issue_uop.valid := 0.U
    io.ld_issue_uop.valid := 0.U
    io.st_issue_uop.valid := 0.U
    state := ooo_mode
  }.elsewhen(state === ooo_mode && still_has_csr) {
    dbg(cf"Now entering csr mode serialised mask ${serialise_mask}%b furthest mask ${furthest_mask}%b fire: ${serialise_fire}%b\n")
    serialise_mask := furthest_mask // Shift the mask to align the most significant bit with the first matching csr instruction within the previous serialise_mask window
    // when(rob_empty) {
      // val next_serialise_mask = (serialise_mask << 1.U)
      // serialise_mask := next_serialise_mask
      // state := ino_mode
    // }.otherwise {
    state:= pre_ino_mode
    // }
  }.elsewhen(state === pre_ino_mode && rob_empty) {
    state:= ino_mode
  }.elsewhen(state === ino_mode) {
    val next_serialise_mask = (serialise_mask << 1.U)
    serialise_mask := next_serialise_mask
    when(serialise_fire_next =/= 0.U) {
      dbg(cf"Now entering csr mode again\n")
      state := ino_mode
    }.otherwise {
      dbg(cf"Now leaving csr mode sm : ${next_serialise_mask}%b\n")
      state := post_ino_mode
    }
  }.elsewhen(state === post_ino_mode) {
    dbg(cf"post ino mode, resetting serialise_mask ${serialise_mask}%b\n")
    state := ooo_mode
    serialise_mask := serialise_mask // Doesn't retain value without this, costed me a fortune in time to find out
  }.elsewhen(state === ooo_mode && input_ready) {
    dbg(cf"Pre ooo mode, resetting serialise_mask, was previously ${serialise_mask}%b\n")
    serialise_mask := ~0.U(p.CORE_WIDTH.W) // Reset back to all 1's
  }

  // Debugging
  import chisel3.experimental.BundleLiterals._
  io.exu_issue_uop.bits.zip(io.dis_uop.bits).zip(is_ex.asBools).foreach{case ((x, y), z) => {
      x.bits.debug := RegNext(Mux(z, y.bits.debug, 0.U.asTypeOf(new InstrDebug)))
  }}
  io.ld_issue_uop.bits.zip(io.dis_uop.bits).zip(is_ld.asBools).foreach{case ((x, y), z) => {
      x.bits.debug := RegNext(Mux(z, y.bits.debug, 0.U.asTypeOf(new InstrDebug)))
  }}
  io.st_issue_uop.bits.zip(io.dis_uop.bits).zip(is_st.asBools).foreach{case ((x, y), z) => {
      x.bits.debug := RegNext(Mux(z, y.bits.debug, 0.U.asTypeOf(new InstrDebug)))
  }}
}


