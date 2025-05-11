package rsd_rv32.frontend

import chisel13._
import chisel13.util._
import rsd_rv32.common._

class Fetch_IO(implicit p: Parameters) extends CustomBundle {
    // with MEM
    val instr_addr = Output(UInt(p.XLEN.W)) //当前IFU的PC值
    val instr = Input(Vec(p.CORE_WIDTH, UInt(32.W)))

    // with ID
    val id_uop = Vec(p.CORE_WIDTH, Valid(new IF_ID_uop()))
    val id_ready = Input(Bool()) //ID是否准备好接收指令

    // with ROB
    val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷
    
    // with BranchPredictor
    // instr_addr上面已写
    val target_PC = Input(UInt(p.XLEN.W)) //预测的下个cycle取指的目标地址
    val btb_hit = Input(Vec(p.CORE_WIDTH, Bool())) //1代表hit，0相反；将最年长的命中BTB的置为1，其余为0
    val branch_pred = Input(Bool()) //branch指令的BHT的预测结果；1代表跳转，0相反
    val GHR = Input(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照
}




class FetchUnit(implicit p: Parameters) extends Module {
    val io = IO(new Fetch_IO())

    val pc_reg = RegInit(0.UInt(p.XLEN.W))        //存储当前PC
    val pc_next = Wire(UInt(p.XLEN.W))            //下一个PC
    val pc_aligned = Wire(UInt(p.XLEN.W))         //对齐后的当前PC

    pc_aligned := pc_reg & ~((p.CORE_WIDTH.U << 2) - 1.U)
    val pc_next_default = pc_aligned + (p.CORE_WIDTH.U <<2)
    
    //需不需要flush
    val rob_flush_valid = io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
    val rob_flush_pc = io.rob_commitsignal(0).bits.instr_addr

    //分支预测
    val whether_take_bp = io.branch_pred
    val bp_target = io.target_PC

    //决定下个pc(ROB>BP>default)
    pc_next := Mux(rob_flush_valid,rob_flush_pc,
                  Mux(whether_take_bp,bp_target,pc_next_default))

    //更新PC寄存器
    when(io.id_ready){
        pc_reg := pc_next
    }
    io.instr_addr := pc_reg
    

    
    //生成两条给ID的uop
    val uop_vec = Wire(Vec(2, new IF_ID_uop()))
    val btb_hit_vec = io.btb_hit
    val hit_11 = (btb_hit_vec === "b11".U)        //如果出现11
    
    //for内
    for (i <-0 until 2 ){
        val uop = Wire(new IF_ID_uop)
        val current_pc = pc_aligned + (i.U << 2)
        uop.instr := io.instr(i)
        uop.instr_addr := current_pc
        uop.target_PC := io.target_pc
        uop.GHR := io.GHR
        uop.branch_pred := Mux(io.branch_pred, BranchPred.T, BranchPred.NT)
        uop.btb_hit := Mux(io.btb_hit(i), BTBHit.H, BTBHit.NH)

        val is_valid = (current_pc >= pc_reg) &&
                    (!rob_flush_valid) &&
                    io.id_ready &&
                    (!(io.btb_hit(i) && io.branch_pred)) &&
                    !(hit_11 && i == 1)
        
        uop.valid := is_valid
        uop_vec(i) := uop
    }


    //存入寄存器给ID
    val id_uop_reg = Reg(Vec(2, Valid(new IF_ID_uop())))
    
    for (i <- 0 until 2) {
        id_uop_reg(i).valid := uop_vec(i).valid
        id_uop_reg(i).bits := uop_vec(i)
        io.id_uop(i) := id_uop_reg(i)
  }

    
    
    
    
}
