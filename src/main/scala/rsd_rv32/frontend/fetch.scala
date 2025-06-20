package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.scheduler._

// 两个输出: instr_addr, id_uop 
class Fetch_IO(implicit p: Parameters) extends CustomBundle {
    // from MEM
    val instr_addr = Valid(UInt(p.XLEN.W)) //当前IFU的PC值
    val instr = Flipped(Vec(p.CORE_WIDTH, UInt(32.W)))

    // to ID
    val id_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new IF_ID_uop())))

    // from ROB
    val rob_controlsignal = Flipped(new ROBControlSignal) //来自于ROB的控制信号
    val rob_commitsignal = Flipped(ROB.CommitSignal) // 用于更新branch_mask
    
    // from BranchPredictor
    val predicted_next_pc = Flipped(UInt(p.XLEN.W)) //预测的下个cycle取指的目标地址
    val btb_hits = Flipped(Vec(p.CORE_WIDTH, Bool())) // 指令屏蔽位
    val should_branch = Flipped(Bool()) //branch指令的BHT的预测结果；1代表跳转，0相反
    val ghr = Flipped(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照

    // toBranchPredictor
    // val ghr_restore = UInt(p.GHR_WIDTH.W) 
    val ghr_update = Valid(Bool())

    // from BranchMask Unit
    val bu_update = Flipped(Valid(new BU_signals))
    val bu_commit = Flipped(Valid(new BU_signals))
    val bu_mask_freelist_deq = Flipped(Vec(p.CORE_WIDTH, Decoupled(UInt(bl(p.BRANCH_MASK_WIDTH)))))
    val bu_mask_freelist_full = Flipped(Bool())
    val current_branch_mask_with_freed = Flipped(UInt(p.BRANCH_MASK_WIDTH.W))
    val branch_freed = Flipped(UInt(p.BRANCH_MASK_WIDTH.W)
)}

object FetchState extends ChiselEnum {
    val awaiting_instr, running = Value
}

// Currently the FetchUnit consists of three stages:
// The first stage being pc_reg,
// the second stage being the memory,
// and the last stage being the output,
// This has the benefit for implementing the branch_predictor with buffered input 
class FetchUnit(implicit p: Parameters) extends CustomModule {
    val io = IO(new Fetch_IO())

    import FetchState._
    val core_state = RegInit(awaiting_instr)
  
    val pc_reg = RegInit(p.ENTRY_PC.U(p.XLEN.W))        //存储当前PC
    
    val pc_next = Wire(UInt(p.XLEN.W))            //下一个PC
    val pc_aligned = pc_reg & ~((p.CORE_WIDTH << 2).U(p.XLEN.W) - 1.U)         //对齐后的当前PC

    val pc_next_default = pc_aligned + (p.CORE_WIDTH.U << 2)

    //需不需要flush
    val should_flush = io.rob_controlsignal.shouldFlush

    //分支预测
    val whether_take_bp = io.should_branch
    val bp_target = io.predicted_next_pc
    
    //决定下个pc(ROB>BP>default)
    pc_next := Mux(whether_take_bp, bp_target, pc_next_default)
    
    val can_allocate_all_branches = Wire(Bool())
    val operation_ready = core_state === running
    val downstream_ready = io.id_uop.ready
    val ack = operation_ready && downstream_ready
     
    // When rob signals flush to all modules, then it will signal not ready to every modules
    // So when shouldFlush is triggered, on the next clock cycle, downstream_ready goes low
    val branches = io.rob_commitsignal.bits.map(e =>  e.valid && e.mispred)
    val has_got_branches = VecInit(branches).asUInt =/= 0.U && io.rob_commitsignal.valid
    val first_branch = PriorityMux(branches, io.rob_commitsignal.bits)
    
    when(!reset.asBool) {
        when(io.rob_controlsignal.shouldFlush || has_got_branches) {
            core_state := awaiting_instr
        }.elsewhen(core_state === awaiting_instr && downstream_ready) {
            core_state := running
        }
        
        //TODO

        when(has_got_branches) {
            pc_reg := first_branch.target_PC
        }.elsewhen(downstream_ready){
            pc_reg := pc_next
        }
    }

    //TODO: Confirm validity
    io.instr_addr.bits := pc_reg
    io.instr_addr.valid := downstream_ready && !should_flush
 
    withReset(reset.asBool || should_flush) {
        val mem_stage_ack = downstream_ready && !should_flush
        
        //为对齐时序而延迟一周期的内容
        val packet_first_valid_slot = pc_reg(log2Ceil(p.CORE_WIDTH - 1) + 2 , 2)
        val instr_valid_mask = Reg(UInt(p.CORE_WIDTH.W))
    
        val offset_mask =  (~0.U(p.CORE_WIDTH.W) << packet_first_valid_slot)(p.CORE_WIDTH - 1, 0)
        when(mem_stage_ack) {
            instr_valid_mask := offset_mask
        }
    
        val current_pc_aligned = RegEnable(pc_aligned, mem_stage_ack)
    
        // 来自BP
        val btb_hits = RegEnable(VecInit((io.btb_hits.asUInt & offset_mask)(p.CORE_WIDTH - 1, 0).asBools), mem_stage_ack)
        val btb_hit_oh = VecInit(PriorityEncoderOH(btb_hits)).asUInt
        val instr_mask_btb = (~(btb_hit_oh | (btb_hit_oh - 1.U))).asBools
        val ghr = RegEnable(io.ghr, mem_stage_ack)
        val should_branch = RegEnable(io.should_branch, mem_stage_ack)
        val predicted_next_pc = RegEnable(io.predicted_next_pc, mem_stage_ack)
        val pc_aligned_deferred = RegEnable(pc_aligned, mem_stage_ack)
        val instr_valid = core_state === running  && !should_flush

        // TODO : Needs to be reconsidered
        val is_br = VecInit(io.instr.map{ case instr => {
            val opcode = Instr.disassemble(instr).opcode 
            //Branch, JAL, JALR
            opcode === (0b1100011.U) || 
            opcode === (0b1101111.U) || 
            opcode === (0b1100111.U) 
        }}).asUInt & VecInit(instr_valid_mask).asUInt 

        io.ghr_update := DontCare
        io.ghr_update.valid := false.B
        when(is_br =/= 0.U) {
            io.ghr_update.bits := should_branch
            io.ghr_update.valid := true.B
        }    
        // end of TODO
        
        // can_allocate_all_branches := !io.bu_mask_freelist_full
        // val has_br = is_br.orR;
        can_allocate_all_branches := is_br.asBools.zip(io.bu_mask_freelist_deq).map{ case (valid, deq) =>
          !valid || !deq.ready || deq.valid
        }.reduce(_ && _)

        var temporary_branch_mask = 0.U(p.BRANCH_MASK_WIDTH.W)
        
        io.id_uop.bits := RegEnable(VecInit(io.instr.zip(instr_valid_mask.asBools).zip(instr_mask_btb).zip(io.bu_mask_freelist_deq).zip(is_br.asBools).zipWithIndex.map{case (((((instr_from_mem, valid), masked), branch_mask_deq), is_br), i) => {
            val uop = Wire(Valid(new IF_ID_uop()))
            val current_pc = pc_aligned_deferred + (i.U << 2)
    
            uop.bits.instr := instr_from_mem
            uop.bits.instr_addr := current_pc
            
            uop.bits.predicted_next_pc := predicted_next_pc
            uop.bits.ghr := ghr
            uop.bits.branch_taken := should_branch
    
            val is_valid = valid && !masked && core_state === running 
            
            uop.valid := is_valid
            
            branch_mask_deq.ready := valid && !masked && core_state === running && is_br && downstream_ready
            // uop.bits.branch_mask := (io.current_branch_mask_with_freed | temporary_branch_mask)
            // uop.bits.branch_id := branch_mask_deq.bits        
            // uop.bits.branch_freed := io.branch_freed

            temporary_branch_mask |= Mux(branch_mask_deq.valid, 1.U << branch_mask_deq.bits, 0.U)
            
            // Debugging
            uop.bits.debug(io.instr(i), current_pc, is_valid)

            uop
        }}), ack)
        
        // TODO: Not sure yet
        io.id_uop.valid := RegEnable(instr_valid && !should_flush, false.B, ack || should_flush)
        // io.id_uop.valid := RegNext(instr_valid && ack)
    }
}

