package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._



/*
class Dispatch_ROB_Interface(implicit p: Parameters) extends Bundle {
    val dis_uops = Valid(Vec(p.DISPATCH_WIDTH, new uop()))  //Dispatch Unit的uop

    val rob_empty = Input(Bool())  //ROB空标志(0表示空，1表示非空)
    val rob_head = Input(UInt(log2Ceil(p.ROB_DEPTH))) //ROB头指针
    val rob_tail = Input(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB尾指针
}

class WB_ROB_Interface(implicit p: Parameters) extends Bundle {
    val complete_map = Input(Vec(p.FU_NUM, Bool()))  //完成映射表
    val complete_uop = Input(Vec(p.FU_NUM, new uop()))  //来自exu的uop
    val mispred = Input(Bool()) //分支误预测信号
    val if_jump = Input(Bool()) //分支指令跳转信号
}

class ROB_broadcast(implicit p: Parameters) extends Bundle {
    val commit_signal = Valid(Vec(p.DISPATCH_WIDTH, UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))) //ROB条目
}
*/

class ROBIO(implicit p: Parameters) extends Bundle {
    val dis_uops = Valid(Vec(p.DISPATCH_WIDTH, new DISPATCH_ROB_uop()))  //Dispatch Unit的uop

    val rob_empty = Input(Bool())  //ROB空标志(0表示空，1表示非空)
    val rob_head = Input(UInt(log2Ceil(p.ROB_DEPTH))) //ROB头指针
    val rob_tail = Input(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB尾指针

    val ALU_complete_uop = Flipped(Valid((Vec(p.ALU_NUM, new ALU_WB_uop()))))  //来自alu的uop
    val BU_complete_uop = Flipped(Valid((Vec(p.BU_NUM, new BU_WB_uop()))))  //来自bu的uop
    val STU_complete_uop = Flipped(Valid(new STPIPE_WB_uop()))  //来自stu的uop
    val LDU_complete_uop = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop
    // val mispred = Input(Bool()) //分支误预测信号
    // val if_jump = Input(Bool()) //分支指令跳转信号

    val commit_signal = Valid(Vec(p.DISPATCH_WIDTH, UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))) //ROB条目
}
// 重命名缓冲区，主要用于存储指令的执行结果。它是一个FIFO结构，先进先出。指令在执行完成后，将结果写入ROB中。ROB中的数据可以被其他指令读取，从而实现数据的共享和重用。
class ROB(implicit p: Parameters) extends Module {
    val io = IO(new ROBIO())
}