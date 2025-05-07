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
    val btb_hit = Input(Vec(p.CORE_WIDTH, Bool())) //1代表hit，0相反；将最年轻的命中BTB的置为1，其余为0
    val branch_pred = Input(Bool()) //branch指令的BHT的预测结果；1代表跳转，0相反
    val GHR = Input(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照
}




class FetchUnit(implicit p: Parameters) extends Module {
    val io = IO(new Fetch_IO())

    val pc_reg = RegInit(0.UInt(p.XLEN.W))        //存储当前PC
    val pc_next = Wire(UInt(p.XLEN.W))            //下一个PC
    val pc_aligned = Wire(UInt(p.XLEN.W))         //对齐后的当前PC
    val whether_flush = Wire(Bool())              //是否需要冲刷一下

    pc_aligned := pc_reg & ~((p.CORE_WIDTH.U << 2) - 1.U)
    val pc_next_default = pc_aligned + (p.CORE_WIDTH.U <<2)
    
    //需不需要flush
    whether_flush := io.rob_commitsignal
    val rob_flush_pc = Mux1H(io.rob_commitsignal.map(s => s.valid -> s.bits.pc))

    //分支预测
    val whether_take_bp = io.branch_pred
    val bp_target = io.target_PC

    //决定下个pc(ROB>BP>default)
    pc_next := Mux(whether_flush,rob_flush_pc,
                  Mux(whether_take_bp,bp_target,pc_next_default))

    //更新PC寄存器
    when(io.id_ready){
        pc_reg := pc_next
    }
    io.instr_addr := pc_reg
    

    
    //生成给ID的uop
    for(i <- 0 until p.CORE_WIDTH){
        val uop = Wire(new IF_ID_uop())

        val current_pc = pc_aligned +(i.U << 2)
        uop.instr_addr := current_pc
        uop.instr := io.instr(i)
        uop.valid := false.B
        uop.GHR := io.GHR

        uop.valid := (current_pc >=pc_reg)&&(!whether_flush)&&(io.id_ready)&&(!(io.btb_hit(i)&&whether_take_bp))
        io.id_uop(i).valid := uop.valid
        io.id_uop(i).bits := uop

        
    }

    
    
    
}
