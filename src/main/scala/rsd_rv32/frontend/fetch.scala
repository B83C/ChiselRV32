package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.scheduler._


// 两个输出: instr_addr, id_uop 
class Fetch_IO(implicit p: Parameters) extends CustomBundle {
    // with MEM
    val instr_addr = (UInt(p.XLEN.W)) //当前IFU的PC值
    val instr = Flipped(Vec(p.CORE_WIDTH, UInt(32.W)))

    // with ID
    val id_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new IF_ID_uop())))

    // with ROB
    val rob_controlsignal = Flipped(Valid(new ROBControlSignal)) //来自于ROB的控制信号
    
    // with BranchPredictor
    val predicted_next_pc = Flipped(UInt(p.XLEN.W)) //预测的下个cycle取指的目标地址
    val btb_hits = Flipped(Vec(p.CORE_WIDTH, Bool())) // 指令屏蔽位
    val should_branch = Flipped(Bool()) //branch指令的BHT的预测结果；1代表跳转，0相反
    val ghr = Flipped(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照
}

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
    val mispred = io.rob_controlsignal.valid && io.rob_controlsignal.bits.isMispredicted
    // val rob_flush_valid = io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
    // TODO
    val rob_mispred_pc = io.rob_controlsignal.bits.PC_next

    //分支预测
    val whether_take_bp = io.should_branch && core_state === running
    val bp_target = io.predicted_next_pc

    //决定下个pc(ROB>BP>default)
    pc_next := Mux(mispred, rob_mispred_pc,
                  Mux(whether_take_bp, bp_target, pc_next_default))

    val operation_ready = core_state === running
    val ack = operation_ready && io.id_uop.ready
    
    when(!reset.asBool) {
        when(mispred) {
            core_state := awaiting_instr
        }.elsewhen(core_state === awaiting_instr) {
            core_state := running
        }
        //TODO
        when(mispred || ack || core_state === awaiting_instr){
            pc_reg := pc_next
        }
    }
    io.instr_addr := pc_aligned

    withReset(reset.asBool || mispred) {
        val mem_stage_ack = core_state === awaiting_instr || ack
        
        //为对齐时序而延迟一周期的内容
        val rob_flush_valid_delayed = RegEnable(mispred, mem_stage_ack)

        val packet_first_valid_slot = pc_reg(log2Ceil(p.CORE_WIDTH - 1) + 2 , 2)
        val instr_valid_mask = Reg(UInt(p.CORE_WIDTH.W))
    
        val offset_mask =  (~0.U(p.CORE_WIDTH.W) << packet_first_valid_slot)(p.CORE_WIDTH - 1, 0)
        instr_valid_mask := offset_mask
    
        val current_pc_aligned = RegEnable(pc_aligned, mem_stage_ack)
    
        // 来自BP
        val btb_hits = RegEnable(VecInit((io.btb_hits.asUInt & offset_mask)(p.CORE_WIDTH - 1, 0).asBools), mem_stage_ack)
        val btb_hit_oh = VecInit(PriorityEncoderOH(btb_hits)).asUInt
        val instr_mask_btb = (~(btb_hit_oh | (btb_hit_oh - 1.U))).asBools
        val ghr = RegEnable(io.ghr, mem_stage_ack)
        val should_branch = RegEnable(io.should_branch, mem_stage_ack)
        val predicted_next_pc = RegEnable(io.predicted_next_pc, mem_stage_ack)
        val pc_aligned_deferred = RegEnable(pc_aligned, mem_stage_ack)
        val instr_valid = operation_ready

        io.id_uop.bits := RegEnable(VecInit(io.instr.zip(instr_valid_mask.asBools).zip(instr_mask_btb).zipWithIndex.map{case (((instr, valid), masked), i) => {
            val uop = Wire(Valid(new IF_ID_uop()))
            val current_pc = pc_aligned_deferred + (i.U << 2)
    
            uop.bits.instr := instr
            uop.bits.instr_addr := current_pc
    
            uop.bits.predicted_next_pc := predicted_next_pc
            uop.bits.ghr := ghr
            uop.bits.branch_taken := should_branch
            // uop.bits.btb_hit := Mux(btb_hit_delayed(i), BTBHit.H, BTBHit.NH)
    
            val is_valid = valid && !masked 
            uop.valid := valid && !masked 
        
            // Debugging
            uop.bits.debug(io.instr(i), current_pc, is_valid)

            uop
        }}), ack)
        
        // TODO: Not sure yet
        io.id_uop.valid := RegEnable(instr_valid, false.B, ack)
    }
}

