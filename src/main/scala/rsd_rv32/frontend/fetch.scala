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
    val instr_mask = Flipped(Vec(p.CORE_WIDTH, Bool())) // 指令屏蔽位
    val should_branch = Flipped(Bool()) //branch指令的BHT的预测结果；1代表跳转，0相反
    val ghr = Flipped(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照
}

object FetchState extends ChiselEnum {
    val rst, awaiting_instr, running, stopped = Value
}

// Currently the FetchUnit consists of three stages:
// The first stage being pc_reg,
// the second stage being the memory,
// and the last stage being the output,
// This has the benefit for implementing the branch_predictor with buffered input 
class FetchUnit(implicit p: Parameters) extends CustomModule {
    val io = IO(new Fetch_IO())

    import FetchState._
    val core_state = RegInit(rst)
  
    val pc_reg = RegInit(p.ENTRY_PC.U(p.XLEN.W))        //存储当前PC
    val pc_next = Wire(UInt(p.XLEN.W))            //下一个PC
    val pc_aligned = pc_reg & ~((p.CORE_WIDTH << 2).U(p.XLEN.W) - 1.U)         //对齐后的当前PC

    val pc_next_default = pc_aligned + (p.CORE_WIDTH.U << 2)

    //需不需要flush
    val rob_flush_valid = io.rob_controlsignal.valid && io.rob_controlsignal.bits.isMispredicted
    // val rob_flush_valid = io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
    // TODO
    val rob_flush_pc = DontCare

    //分支预测
    val whether_take_bp = io.should_branch
    val bp_target = io.predicted_next_pc

    //决定下个pc(ROB>BP>default)
    pc_next := Mux(rob_flush_valid, rob_flush_pc,
                  Mux(whether_take_bp, bp_target, pc_next_default))

    //更新PC寄存器
    when(!reset.asBool) {
        when(core_state === rst) {
            core_state := awaiting_instr
        }.elsewhen(core_state === awaiting_instr) {
            core_state := running
        }
        when(io.id_uop.ready && core_state =/= rst){
            pc_reg := pc_next
        }
    }
    io.instr_addr := pc_reg

    //为对齐时序而延迟一周期的内容
    val rob_flush_valid_delayed = RegNext(rob_flush_valid)
    
    // val PC_delayed = RegNext(pc_aligned)
    // val GHR_delayed = RegNext(io.ghr)
    // val branch_pred_delayed = RegNext(io.should_branch)
    // val target_PC_delayed = RegNext(io.predicted_next_pc)

    val packet_first_valid_slot = pc_reg(log2Ceil(p.CORE_WIDTH - 1) + 2 , 2)
    val instr_valid = Reg(UInt(p.CORE_WIDTH.W))
    
    instr_valid := (~0.U(p.CORE_WIDTH.W) << packet_first_valid_slot)(p.CORE_WIDTH - 1, 0)
    val current_pc_aligned = RegNext(pc_aligned)
    
    val instr_mask = RegNext(io.instr_mask)
    val ghr = RegNext(io.ghr)
    val should_branch = RegNext(io.should_branch)
    val predicted_next_pc = RegNext(io.predicted_next_pc)

    io.id_uop.bits.zip(io.instr).zip(instr_valid.asBools).zip(instr_mask).zipWithIndex.foreach{case ((((id_uop, instr), valid), masked), i) => {
        val uop = Wire(new IF_ID_uop())
        val current_pc = current_pc_aligned + (i.U << 2)
    
        uop.debug := DontCare 
        uop.instr := instr
        uop.instr_addr := current_pc
    
        uop.predicted_next_pc := predicted_next_pc
        uop.ghr := ghr
        uop.branch_taken := should_branch
        // uop.btb_hit := Mux(btb_hit_delayed(i), BTBHit.H, BTBHit.NH)
    
        val is_valid = valid && !masked 

        // TODO
        io.id_uop.bits(i).valid := RegNext(is_valid)
        io.id_uop.bits(i).bits := RegEnable(uop, is_valid)
        
        // Debugging
        io.id_uop.bits(i).bits.debug(io.instr(i), current_pc, is_valid)
    }}
    io.id_uop.valid := RegNext(core_state === running)
}

