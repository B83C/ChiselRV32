package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class exu_issue_IO(implicit p: Parameters) extends Bundle {
    //来自Dispatch Unit的输入
    // val iq_id = Input(Vec(p.DISPATCH_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //IQ ID,
    val dis_uop = Flipped(Valid(Vec(p.DISPATCH_WIDTH, new DISPATCH_EXUISSUE_uop())))  //来自Dispatch Unit的输入

    //发射到ALU的输出
    val FU_ready = Input(Vec(p.ALU_NUM, Bool())) //功能单元的ready信号
    val dst_FU = Output(Vec(p.ISSUE_WIDTH, UInt(log2Ceil(p.ALU_NUM).W)))  //发射的指令的目标功能单元
    val issue_uop = Valid(Vec(p.ISSUE_WIDTH, new EXUISSUE_EXU_uop()))  //发射的指令(包含操作数的值)
    // val value_o1 = Output(Vec(p.ISSUE_WIDTH, UInt(p.XLEN.W))) //发射的指令的操作数1
    // val value_o2 = Output(Vec(p.ISSUE_WIDTH, UInt(p.XLEN.W))) //发射的指令的操作数2

    //PRF
    val raddr1 = Output(Vec(p.EXU_FU_NUM, UInt(log2Ceil(p.PRF_DEPTH).W))) //PRF读地址1
    val raddr2 = Output(Vec(p.EXU_FU_NUM, UInt(log2Ceil(p.PRF_DEPTH).W))) //PRF读地址2
    val value_i1 = Input(Vec(p.ISSUE_WIDTH, UInt(p.XLEN.W))) //操作数1
    val value_i2 = Input(Vec(p.ISSUE_WIDTH, UInt(p.XLEN.W))) //操作数2    

    //监听PRF的valid信号用于更新ready状态
    val prf_valid = Input(Vec(p.PRF_DEPTH, Bool())) //PRF的valid信号
    //监听FU后级间寄存器内的物理寄存器ready信号
    val ALU_complete_uop2 = Flipped(Valid((Vec(p.ALU_NUM, new ALU_WB_uop()))))  //来自alu的uop
    val LDU_complete_uop2 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop
    //监听FU处物理寄存器的ready信号
    val ALU_complete_uop1 = Flipped(Valid((Vec(p.ALU_NUM, new ALU_WB_uop()))))  //来自alu的uop
    val LDU_complete_uop1 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop

    //输出至Dispatch Unit的信号
    val iq_freelist_update = Output(Vec(p.ISSUE_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //更新IQ Freelist

    //with ROB
    val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷
}

class exu_issue_queue(implicit p: Parameters) extends Module {
    val io = IO(new exu_issue_IO())
}