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
    val dis_uops = Valid(Vec(p.CORE_WIDTH, new DISPATCH_ROB_uop()))  //Dispatch Unit的uop,存入条目中

    val empty_full = Output(Bool())  //ROB空标志(0表示非满，1表示满)
    val rob_head = Output(UInt(log2Ceil(p.ROB_DEPTH))) //ROB头指针
    val rob_tail = Output(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB尾指针

    val ALU_complete_uop = Flipped(Valid((Vec(p.ALU_NUM, new ALU_WB_uop()))))  //来自alu的uop,更新就绪状态
    val BU_complete_uop = Flipped(Valid((Vec(p.BU_NUM, new BU_WB_uop()))))  //来自bu的uop,更新就绪状态
    val jal = Bool()
    val jalr = Bool()
    val STU_complete_uop = Flipped(Valid(new STPIPE_WB_uop()))  //来自stu的uop,更新就绪状态
    val LDU_complete_uop = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop,更新就绪状态
    // val mispred = Input(Bool()) //分支误预测信号
    // val if_jump = Input(Bool()) //分支指令跳转信号

    val commit_signal = Vec(p.CORE_WIDTH, Valid(new ROBContent()))  //广播ROB条目
}

class ROB(implicit p: Parameters) extends Module {
    val io = IO(new ROBIO())
}
