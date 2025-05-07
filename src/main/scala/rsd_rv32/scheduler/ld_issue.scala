package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class ld_issue_IO(implicit p: Parameters) extends CustomBundle {
    //来自Dispatch Unit的输入
    val dis_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new DISPATCH_LDISSUE_uop())))  //来自Dispatch Unit的输入

    //发射到ld的输出
    val ld_issue_uop = Decoupled(new LDISSUE_LDPIPE_uop())  //发射的指令
    // val value_o1 = Output(UInt(p.XLEN.W)) //发射的指令的操作数1
    // val value_o2 = Output(UInt(p.XLEN.W)) //发射的指令的操作数2

    //PRF
    val ld_issue_raddr1 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址1
    //val raddr2 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址2
    val ld_issue_value1 = Input(UInt(p.XLEN.W)) //操作数1
    //val value_i2 = Input(UInt(p.XLEN.W)) //操作数2    

    //监听PRF的valid信号用于更新ready状态
    val prf_valid = Input(Vec(p.PRF_DEPTH, Bool())) //PRF的valid信号
    //监听FU后级间寄存器内的物理寄存器ready信号
    val wb_uop2 = Flipped(Valid((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, new ALU_WB_uop()))))  //来自alu、mul、div、load pipeline的uop
    //val ldu_wb_uop2 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop
    //监听FU处物理寄存器的ready信号
    val wb_uop1 = Flipped(Valid((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, new ALU_WB_uop())))) //来自alu、mul、div、load pipeline的uop
    //val ldu_wb_uop1 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop

    //输出至Dispatch Unit的信号
    val ld_issued_index = Output(UInt(log2Ceil(p.LDISSUE_DEPTH).W)) //更新IQ Freelist

    //with ROB
    val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷
}

class ld_issue_queue(implicit p: Parameters) extends Module {
    val io = IO(new ld_issue_IO())
}