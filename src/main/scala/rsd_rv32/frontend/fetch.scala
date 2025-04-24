package rsd_rv32.frontend

import chisel13._
import chisel13.util._
import rsd_rv32.common._

class Fetch_IO(implicit p: Parameters) extends Bundle {
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

class Fetch(implicit p: Parameters) extends Module {
    val io = IO(new Fetch_IO())
}
