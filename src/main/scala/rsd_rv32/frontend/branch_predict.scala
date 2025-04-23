package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._

/*class BP_IFU_Interface(implicit p: Parameters) extends Bundle {
    val PC_cur = Input(UInt(p.XLEN.W)) //当前IFU的PC值
    val PC_target = Output(UInt(p.XLEN.W)) //预测的下个cycle取指的目标地址
    val BTB_Hit = Output(Vec(p.CORE_WIDTH, Bool())) //1代表hit，0相反；将最年轻的命中BTB的置为1，其余为0
    val BHT_Taken = Output(Bool()) //branch指令的BHT的预测结果；1代表跳转，0相反
    val GHR = Output(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照
}

class BP_ROB_Interface(implicit p: Parameters) extends Bundle {
    val PC = Input(UInt(p.XLEN.W)) //当前ROB的PC值
    val instrType = Input(UInt(3.W)) //当前指令类型,该模块需要区分条件分支和无条件分支
    val BTB_Hit = Input(Bool()) //该分支指令最初是否命中BTB
    val actual_Taken = Input(Bool()) //实际是否taken
    val GHR = Input(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照，使得更新BHT时能够生成正确的index
    val actualTargetPC = Input(UInt(p.XLEN.W)) //目标地址
} */

class BP_IO (implicit p: Parameters) extends Bundle {
    //with IF
    val instrAddr = Input(UInt(p.XLEN.W)) //当前PC值，用于访问BTB获得跳转目标地址，以及访问BHT获得跳转预测结果
    val PC_target = Output(UInt(p.XLEN.W)) //预测的下个cycle取指的目标地址
    val BTB_Hit = Output(Vec(p.CORE_WIDTH, Bool())) //1代表hit，0相反；将指令包中命中BTB的最年轻的置为1，其余为0
    val BHT_Taken = Output(Bool()) //条件分支指令的BHT的预测结果；1代表跳转，0相反；非条件分支置1
    val GHR = Output(UInt(p.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照，随流水级传递，在ROB退休分支指令时更新BHT

    //with ROB
    val rob_commitsignal = Valid(Vec(p.DISPATCH_WIDTH, UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))).flip //ROB提交时的广播信号，从中识别出分支指令更新BHT和BTB
}

/*
本模块采用bi-mode分支预测算法，采用BTB + BHT结合的方式工作。主要维护：1、BTB 2、BHT（内含T表和NT表，选择器）
    取指单元送来当前取指地址PC，以PC和PC+4（取指宽度为2）索引BTB，若为条件分支指令（该信息存储在BTB中），则用PC与GHR的索引函数索引BHT，得到预测结果，并更新GHR（此时的GHR为推测执行的GHR）。
若为无条件分支指令，则直接将BTB中索引到的目标地址发送给取指单元即可。
    branch predictor单元通过ROB广播的信号来更新BTB，BHT以及修正GHR(如果发生误预测)。
*/

class BranchPredictor(implicit p: Parameters) extends Module {
    val io = IO(new BP_IO())
}
