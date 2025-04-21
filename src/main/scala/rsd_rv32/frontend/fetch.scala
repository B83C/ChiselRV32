package rsd_rv32.frontend

import chisel13._
import chisel13.util._
import rsd_rv32.common._

class Fetch_IO(implicit p: Parameters) extends Bundle {
    // with MEM
    val instAddr = Output(UInt(p.XLEN.W)) //当前IFU的PC值
    val instr = Input(Vec(p.FETCH_WIDTH, UInt(32.W)))

    // with ID
    val id_uop = Decoupled(Vec(p.FETCH_WIDTH, new IF_ID_uop()))

    // with ROB
    val rob_commitsignal = Valid(Vec(p.DISPATCH_WIDTH, UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))).flip
    
    // with BranchPredictor
    // instAddr上面已写
    val PC_target = Input(UInt(p.XLEN.W)) //预测的下个cycle取指的目标地址
    val BTB_Hit = Input(Vec(p.FETCH_WIDTH, Bool())) //1代表hit，0相反；将最年轻的命中BTB的置为1，其余为0
    val BHT_Taken = Input(Bool()) //branch指令的BHT的预测结果；1代表跳转，0相反
    val GHR = Input(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照

    // with ID
    val id_uop = Decoupled(Vec(p.FETCH_WIDTH, new IF_ID_uop()))
}
