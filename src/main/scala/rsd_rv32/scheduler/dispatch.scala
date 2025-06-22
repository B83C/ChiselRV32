package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

import Utils._

class Dispatcher_IO(exu_fu_num: Int)(implicit p:Parameters) extends CustomBundle {
  // from Rename
  val dis_uop = Flipped(Decoupled(Vec(p.CORE_WIDTH, Flipped(Valid(new RENAME_DISPATCH_uop()))))) //来自rename单元的uop
  
  // to ROB
  val rob_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new DISPATCH_ROB_uop()))) //发往ROB的uop
  
  // from ROB
  val rob_index = Flipped(UInt(bl(p.ROB_DEPTH) + 1.W)) //ROB尾指针
  val rob_empty = Flipped(Bool()) //ROB尾指针
  val rob_controlsignal = Flipped(new ROBControlSignal) //来自于ROB的控制信号
  
  // to EXU
  val serialised_uop = Valid(new SERIALISED_uop()) // 只有在顺序执行模式下才有效
  
  // to exu_issue
  val exu_issue_uop = Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop()))) //发往EXU的uop
  val exu_issued_index = Flipped(Vec(exu_fu_num, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W)))) //本周期EXU ISSUE queue发出指令对应的issue queue ID，用于更新iq freelist
  
  // to st_issue
  val st_issue_uop = Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_STISSUE_uop()))) //发往Store Issue的uop
  val st_issued_index = Flipped(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))//本周期Store Issue queue发出指令对应的issue queue ID，用于更新issue queue freelist
  
  // to ld_issue
  val ld_issue_uop = Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_LDISSUE_uop()))) //发往Load Issue的uop
  val ld_issued_index = Flipped(Valid(UInt(log2Ceil(p.LDISSUE_DEPTH).W)))//本周期Load Issue queue发出指令对应的issue queue ID，用于更新issue queue freelist
  
  // from stq
  val stq_full = Flipped(Bool()) // Store Queue空标志(0表示非满，1表示满)
  val stq_tail = Flipped(UInt(bl(p.STQ_DEPTH)+ 1.W)) //store queue尾部指针，指向入队处
  
  // to stq
  val st_inc = Valid(UInt(log2Ceil(p.CORE_WIDTH + 1).W)) //本cycle派遣store指令的数量
}

// ooo_mode (Out of order) 表示乱序执行状态
// ino_mode (In order) 表示顺序执行状态
object DispatchState extends ChiselEnum {
  val ooo_mode, ino_mode, post_ino_mode = Value
}

// 该模块负责将重命名单元发来的指令派遣到ROB，根据指令类型派遣到三个issue queue（分别为exu issue queue，store issue queue和load issue queue），如果是store指令，则还需要派遣到STQ（store queue）中。
// 该模块通过和三个issue queue分别共同维护一个freelist（不是FIFO！），freelist到head和tail之间为可用的issue queue ID，dispatch单元选择head处的issue queue id进行派遣，issue queue发射指令后会将issue queue id
// 反馈给dispatch单元，dispatch单元会将该id存入freelist的tail处。
class DispatchUnit(exu_fu_num: Int)(implicit p: Parameters) extends CustomModule {

  val io = IO(new Dispatcher_IO(exu_fu_num))

  import DispatchState._
  
  val should_flush = io.rob_controlsignal.shouldFlush

  
  val exu_freelist = Module(new FreeListCam(p.EXUISSUE_DEPTH, p.CORE_WIDTH, exu_fu_num)) 
  val ld_freelist = Module(new FreeListCam(p.LDISSUE_DEPTH, p.CORE_WIDTH, 1)) 
  val st_freelist = Module(new FreeListCam(p.STISSUE_DEPTH, p.CORE_WIDTH, 1)) 
  
  val can_dispatch = Wire(Bool())
  val input_valid = io.dis_uop.valid
  val downstream_ready = io.rob_uop.ready && !(io.stq_full || exu_freelist.io.full || ld_freelist.io.full || st_freelist.io.full)
  
  withReset(should_flush || reset.asBool) {
    
    val state = RegInit(ooo_mode)
    val is_csr = VecInit(io.dis_uop.bits.map(x => x.valid && x.bits.instr_type.isOneOf(InstrType.CSR))).asUInt
  
    // val ooo_mask = 
    val serialise_mask = Reg(UInt(p.CORE_WIDTH.W)) // All 1's by default
    val still_has_csr = (serialise_mask & is_csr) =/= 0.U && input_valid
    serialise_mask := ~0.U(p.CORE_WIDTH.W)

    def sig_mask(value: UInt) : UInt = {
      val mid = PriorityEncoderOH(value) - 1.U
      mid(p.CORE_WIDTH - 1 , 0)
    }

    val clamped_is_csr = Cat(1.U, serialise_mask & is_csr) // 1.U is padded at the furthest, note this is little endian
    val closest_mask = sig_mask(clamped_is_csr)  // Generates 1's mask that appear right before the next csr instruction, the clamped_is_csr is clamped to make sure when csr is not detected till the end of the packet, it is handled correctly (that closest_mask should be 1's for all the left non-csr instructions)
    val furthest_mask = ~closest_mask

    val ooo_mask = closest_mask & serialise_mask // Clamped within serialise mask to make sure ooo instructions dispatched before don't appear again

    val serialise_mask_mb = PriorityEncoderOH(serialise_mask) 
    val serialise_fire = serialise_mask_mb & is_csr  
    val serialise_fire_next = (serialise_mask_mb << 1) & is_csr 
  
    val selected_serialising_uop = Mux1H(serialise_fire.asBools, io.dis_uop.bits) 
  
    val can_dispatch_serialised = serialise_fire =/= 0.U && state === ino_mode && io.rob_empty && !should_flush
    val serialised_uop_w = Reg(new SERIALISED_uop())
    when(can_dispatch_serialised) {
      (serialised_uop_w: Data).waiveAll :<>= (selected_serialising_uop.bits: Data).waiveAll
    } 
    io.serialised_uop.bits := serialised_uop_w
    io.serialised_uop.valid := RegNext(can_dispatch_serialised)
      
    import InstrType._
    //TODO: Hack
    val is_st = Mux(state === ooo_mode,
      (VecInit(io.dis_uop.bits.map(x => (x.bits.instr_type.isOneOf(ST)) && x.valid)).asUInt & ooo_mask),
      0.U)
    val is_ld = Mux(state === ooo_mode,
      (VecInit(io.dis_uop.bits.map(x => (x.bits.instr_type.isOneOf(LD)) && x.valid)).asUInt & ooo_mask),
      0.U)
    val is_ex = Mux(state === ooo_mode,
      (VecInit(io.dis_uop.bits.map(x => ~(x.bits.instr_type.isOneOf(ST, LD)) && x.valid)).asUInt & ooo_mask),
      0.U)

    exu_freelist.io.enq_request := io.exu_issued_index
    ld_freelist.io.enq_request := VecInit(io.ld_issued_index)
    st_freelist.io.enq_request := VecInit(io.st_issued_index)
  
    val output_valid_w = Wire(Bool())
    val can_allocate_all_ex = exu_freelist.io.deq_request.map(_.valid).reduce(_ && _)
    val can_allocate_all_ld = ld_freelist.io.deq_request.map(_.valid).reduce(_ && _)
    val can_allocate_all_st = st_freelist.io.deq_request.map(_.valid).reduce(_ && _)
    (exu_freelist.io.deq_request zip is_ex.asBools).foreach{ case(dq, v) =>
      dq.ready := v && downstream_ready && input_valid && can_allocate_all_ex && output_valid_w
     }  
    (ld_freelist.io.deq_request  zip is_ld.asBools).foreach{ case(dq, v) =>
      dq.ready := v && downstream_ready && input_valid && can_allocate_all_ld && output_valid_w
    } 
    (st_freelist.io.deq_request  zip is_st.asBools).foreach{ case(dq, v) =>
      dq.ready := v && downstream_ready && input_valid && can_allocate_all_st && output_valid_w
    } 
  
    // Ready fields should be ready when it is not valid, or when it is a valid instruction and freelist is ready
    val ex_req_ready = (exu_freelist.io.deq_request zip is_ex.asBools).map{ case(dq, v) =>
      (dq.valid) || !v 
    }.reduce(_ && _)  
    val ld_req_ready = (ld_freelist.io.deq_request  zip is_ld.asBools).map{ case(dq, v) =>
      (dq.valid) || !v
    }.reduce(_ && _)
    val st_req_ready = (st_freelist.io.deq_request  zip is_st.asBools).map{ case(dq, v) =>
      (dq.valid) || !v
    }.reduce(_ && _) 

    // TODO: They should not be here
    exu_freelist.io.squeeze := should_flush
    ld_freelist.io.squeeze := should_flush
    st_freelist.io.squeeze := should_flush

    can_dispatch := 
        can_allocate_all_ex && 
        can_allocate_all_ld && 
        can_allocate_all_st && 
       // ex_req_ready &&
       // ld_req_ready &&
       // st_req_ready &&
       !should_flush &&
       state === ooo_mode // TODO can be omitted
       
    //TODO: Should be something else? Or is it sufficient
    io.dis_uop.ready := can_dispatch && downstream_ready && !still_has_csr
  
    //Checks for every single uop, signals to update each output register
    val output_valid = ooo_mask =/= 0.U && can_dispatch && input_valid  && downstream_ready
    val st_out_valid = is_st =/= 0.U && output_valid
    val ld_out_valid = is_ld =/= 0.U && output_valid
    val ex_out_valid = is_ex =/= 0.U && output_valid

    output_valid_w := output_valid
  
    io.st_inc.bits := PopCount(is_st.asBools)
    io.st_inc.valid := st_out_valid
  
    io.rob_uop.valid := output_valid
    // TODO invalidating csrs this way is incorrect
    io.rob_uop.bits := VecInit(io.dis_uop.bits.zip(is_csr.asBools).map{case (c, csr) =>
      val out = Wire(Valid(new DISPATCH_ROB_uop()))
      out.valid := c.valid && !csr
      (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
      out
    })

    io.exu_issue_uop:= ValidPassthrough(VecInit(io.dis_uop.bits.zip(is_ex.asBools).zip(exu_freelist.io.deq_request).zipWithIndex.map{case (((c, is_ex), fl), rob_inner_ind) =>
      val out = Wire(Valid(new DISPATCH_EXUISSUE_uop()))
      out.valid := c.valid && is_ex
      (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
      
      out.bits.iq_index := fl.bits
      out.bits.rob_index := io.rob_index
      out.bits.rob_inner_index := rob_inner_ind.U
      out
    }), ex_out_valid)
  
    //TODO 如果用bitmask解决效率更高
    var preceding_st_cnt = 0.U
    var stq_mask = 0.U
    io.ld_issue_uop:= ValidPassthrough(VecInit(io.dis_uop.bits.zip(is_ld.asBools).zip(is_st.asBools).zip(ld_freelist.io.deq_request).zip(st_freelist.io.deq_request).zipWithIndex.map{case (((((input, is_ld), is_st), ld_fl), st_fl), rob_inner_ind) =>
      val out = Wire(Valid(new DISPATCH_LDISSUE_uop()))
      
      // Instruction本身的valid以及instruction 是否为load
      out.valid := input.valid && is_ld
      (out.bits: Data).waiveAll :<>= (input.bits : Data).waiveAll //TODO: UNSAFE 
      out.bits.iq_index := ld_fl.bits
      // TODO
      // out.bits.stq_mask := Mux(preceding_st_cnt === 0.U, 0.U, ((2.U << preceding_st_cnt) - 1.U) << io.stq_tail)
      out.bits.stq_mask := stq_mask
      out.bits.stq_tail := io.stq_tail + preceding_st_cnt//TODO: Check on its validity
      preceding_st_cnt = preceding_st_cnt + is_st
      stq_mask = stq_mask | Mux(is_st && st_fl.valid, 1.U << st_fl.bits, 0.U)
      
      out.bits.rob_index := io.rob_index
      out.bits.rob_inner_index := rob_inner_ind.U
      out
    }), ld_out_valid)
  
    var stq_tail_offset = 0.U
    io.st_issue_uop:= ValidPassthrough(VecInit(io.dis_uop.bits.zip(is_st.asBools).zip(st_freelist.io.deq_request).zipWithIndex.map{case (((c, is_st), fl), rob_inner_ind) =>
      val out = Wire(Valid(new DISPATCH_STISSUE_uop()))

      val valid = c.valid && is_st 
      out.valid := valid
      (out.bits: Data).waiveAll :<>= (c.bits : Data).waiveAll //TODO: UNSAFE 
      out.bits.iq_index := fl.bits
      
      out.bits.rob_index := io.rob_index
      out.bits.rob_inner_index := rob_inner_ind.U
      // TODO 
      out.bits.stq_index := stq_tail_offset + io.stq_tail //TODO: Check on its validity
      stq_tail_offset = stq_tail_offset + valid.asUInt
      out
    }), st_out_valid)

    when(state === ooo_mode && still_has_csr) {
      serialise_mask := furthest_mask // Shift the mask to align the most significant bit with the first matching csr instruction within the previous serialise_mask window
      state:= ino_mode
    }.elsewhen(state === ino_mode && io.rob_empty) {
      val next_serialise_mask = (serialise_mask << 1.U)
      serialise_mask := next_serialise_mask
      when(serialise_fire_next =/= 0.U) {
        state := ino_mode
      }.otherwise {
        state := post_ino_mode
      }
    }.elsewhen(state === post_ino_mode) {
      state := ooo_mode
      serialise_mask := serialise_mask
    }.elsewhen(state === ooo_mode || serialise_mask === 0.U) {
      serialise_mask := ~0.U(p.CORE_WIDTH.W) // Reset back to all 1's
    }
  }
}


