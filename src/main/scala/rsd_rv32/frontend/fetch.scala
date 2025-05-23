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
    val id_uop = Vec(p.CORE_WIDTH, Valid(new IF_ID_uop()))
    val id_ready = Flipped(Bool()) //ID是否准备好接收指令

    // with ROB
    val rob_controlsignal = Flipped(Valid(new ROBControlSignal)) //来自于ROB的控制信号
    
    // with BranchPredictor
    // instr_addr上面已写
    val target_PC = Flipped(UInt(p.XLEN.W)) //预测的下个cycle取指的目标地址
    val btb_hit = Flipped(Vec(p.CORE_WIDTH, Bool())) //1代表hit，0相反；将最年长的命中BTB的置为1，其余为0
    val branch_pred = Flipped(Bool()) //branch指令的BHT的预测结果；1代表跳转，0相反
    val GHR = Flipped(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照
}

object FetchState extends ChiselEnum {
    val rst, entry_pc, running, stopped = Value
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
    val whether_take_bp = io.branch_pred
    val bp_target = io.target_PC

    //决定下个pc(ROB>BP>default)
    pc_next := Mux(rob_flush_valid, rob_flush_pc,
                  Mux(whether_take_bp, bp_target, pc_next_default))

    //更新PC寄存器
    when(!reset.asBool) {
        when(core_state === rst) {
            core_state := entry_pc
        }.elsewhen(core_state === entry_pc) {
            core_state := running
        }
        when(io.id_ready && core_state =/= rst){
            pc_reg := pc_next
        }
    }
    io.instr_addr := pc_reg

    //为对齐时序而延迟一周期的内容
    val rob_flush_valid_delayed = RegNext(rob_flush_valid)
    val PC_delayed = RegNext(pc_aligned)
    val btb_hit_delayed = RegNext(io.btb_hit)
    val GHR_delayed = RegNext(io.GHR)
    val branch_pred_delayed = RegNext(io.branch_pred)
    val target_PC_delayed = RegNext(io.target_PC)

   
    for (i <- 0 until p.CORE_WIDTH) {
        val uop = Wire(new IF_ID_uop())
        val current_pc = PC_delayed + (i.U << 2)
        
        uop.debug := DontCare 
        uop.instr := io.instr(i)
        uop.instr_addr := current_pc
        
        uop.target_PC := target_PC_delayed
        uop.GHR := GHR_delayed
        uop.branch_pred := Mux(branch_pred_delayed, BranchPred.T, BranchPred.NT)
        uop.btb_hit := Mux(btb_hit_delayed(i), BTBHit.H, BTBHit.NH)
        
        val is_valid = (!rob_flush_valid_delayed) && io.id_ready &&
          (!(btb_hit_delayed(i) && branch_pred_delayed)) && core_state === running

        io.id_uop(i).valid := RegNext(is_valid)
        io.id_uop(i).bits := RegEnable(uop, is_valid)


        // Debugging
        io.id_uop(i).bits.debug.instr := RegEnable(io.instr(i), is_valid)
        io.id_uop(i).bits.debug.pc := RegEnable(current_pc, is_valid)
    }

    when(io.id_ready && core_state === running) {
        // val instr1 = instr_delayed(0).asUInt
        // val instr2 = instr_delayed(1).asUInt
        // printf(cf"PC: ${PC_delayed} Got instruction ${instr1}%x ${instr2}%x\n")
    }
}

