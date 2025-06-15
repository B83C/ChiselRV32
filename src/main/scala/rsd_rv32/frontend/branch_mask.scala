package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.scheduler._

// 两个输出: instr_addr, id_uop 
class BranchMask_IO(implicit p: Parameters) extends CustomBundle {
    // from ROB
    val rob_commitsignal = Flipped(ROB.CommitSignal) // 用于更新branch_mask
    val rob_controlsignal = Flipped(new ROBControlSignal) // 用于更新branch_mask
    
    // to everyone else
    val bu_update = Valid(new BU_signals)
    val bu_commit = Valid(new BU_signals)
    
    // from IF
    val bu_mask_freelist_deq = Vec(p.CORE_WIDTH, Decoupled(UInt(bl(p.BRANCH_MASK_WIDTH))))
    
    // to IF
    val current_branch_mask_with_freed = UInt(p.BRANCH_MASK_WIDTH.W)
    val branch_freed = UInt(p.BRANCH_MASK_WIDTH.W)
        
    // from BFU
    val bu_signals = Flipped(Valid(new BU_signals))
}

class BranchMask(implicit p: Parameters) extends CustomModule {
    val io = IO(new BranchMask_IO())
    
    val free_branch_mask = RegInit(0.U(p.BRANCH_MASK_WIDTH.W))
    val current_branch_mask = Wire(UInt(p.BRANCH_MASK_WIDTH.W))
    
    val trigger = io.bu_signals.valid
    val new_bu_signal = io.bu_signals.bits
    val bu_signal_buf = Reg(Valid(new BU_signals))

    val add_free_branch_mask = trigger && !io.bu_signals.bits.mispred
    val new_free_branch_mask =  1.U << io.bu_signals.bits.branch_id

    val uncommited_free_branch_mask = free_branch_mask | Mux(add_free_branch_mask, new_free_branch_mask, 0.U)
    
    val current_branch_mask_with_freed = current_branch_mask & ~uncommited_free_branch_mask

    io.current_branch_mask_with_freed := current_branch_mask_with_freed
    
    // 只有比当前branch更老的那些判断错误的branch/jump指令才能替代
    val should_update = trigger && new_bu_signal.mispred && (!bu_signal_buf.valid || (new_bu_signal.branch_mask.asUInt & (1.U << bu_signal_buf.bits.branch_id)) === 0.U) 

    val commit_buf = bu_signal_buf.valid && (current_branch_mask_with_freed & bu_signal_buf.bits.branch_mask.asUInt) === 0.U
    val commit_current = io.bu_signals.valid && (current_branch_mask_with_freed & new_bu_signal.branch_mask.asUInt) === 0.U
    val should_commit = (trigger && commit_buf) || (should_update && commit_current) 
    // val should_commit = RegNext(should_update) && (current_branch_mask_with_freed & bu_signal_buf.bits.branch_mask.asUInt) === 0.U

    io.bu_update.valid := should_update
    io.bu_update.bits := new_bu_signal
    io.bu_commit.valid := should_commit 
    io.bu_commit.bits := Mux(commit_buf, bu_signal_buf.bits, new_bu_signal)
    
    io.branch_freed := Mux(trigger, 1.U << io.bu_signals.bits.branch_id, 0.U) 
    
    when(should_update) {
        bu_signal_buf.bits := new_bu_signal
        bu_signal_buf.valid := true.B
    }
    when(io.rob_controlsignal.restore_amt) {
        bu_signal_buf.valid := false.B
    }

    val branch_mask_freelist = Module(new FreeListCam(
        p.BRANCH_MASK_WIDTH,
        p.CORE_WIDTH,
        1,
        sharedState = true,
        directFree = true,
    ))
    
    current_branch_mask := branch_mask_freelist.io.state.get.asUInt
    
    branch_mask_freelist.io.enq_request(0).valid := false.B
    branch_mask_freelist.io.enq_request(0).bits := DontCare

    branch_mask_freelist.io.deq_request.zip(io.bu_mask_freelist_deq).foreach{
        case (freelist, request) => {
            request.bits := freelist.bits
            request.valid := freelist.valid
            freelist.ready := request.ready
        }
    }
    
    branch_mask_freelist.io.squeeze := should_commit 

    
    val first_valid_commit_branch_freed = PriorityMux(io.rob_commitsignal.bits.map(_.valid), io.rob_commitsignal.bits.map(_.branch_freed))
    // val last_valid_commit_branch_mask = PriorityMux(io.rob_commitsignal.bits.map(_.valid).reverse, io.rob_commitsignal.bits.map(_.branch_mask).reverse)
    // val branch_mask_to_be_freed = (last_valid_commit_branch_mask & uncommited_free_branch_mask) ^ uncommited_free_branch_mask
    
    branch_mask_freelist.io.enq_request_direct.get.valid := io.rob_commitsignal.valid
    branch_mask_freelist.io.enq_request_direct.get.bits := first_valid_commit_branch_freed    

    when(should_commit) {
        free_branch_mask := 0.U
    }.elsewhen(io.rob_commitsignal.valid) {
        free_branch_mask := (uncommited_free_branch_mask & ~first_valid_commit_branch_freed)  
    }.elsewhen(add_free_branch_mask)(
        free_branch_mask := uncommited_free_branch_mask
    )
}

