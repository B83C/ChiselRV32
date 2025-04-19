package rsd_rv32.scheduler
import chisel3._
import chisel3.util._
import rsd_rv32.common._

class exu_issue_IO(implicit p: Parameters) extends Bundle {
    //来自Dispatch Unit的输入
    val iq_id = Input(Vec(p.DISPATCH_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //IQ ID
    val dis_valid = Output(Vec(p.DISPATCH_WIDTH, Bool()))  //来自Dispatch Unit的有效信号
    val dis_uop = Output(Vec(p.DISPATCH_WIDTH, new uop()))  //来自Dispatch Unit的输入

    //发射到执行单元的输出
    val dst_FU = Output(Vec(p.ISSUE_WIDTH, UInt(log2Ceil(p.FU_NUM).W)))  //发射的指令的目标功能单元
    val issue_uop = Output(Vec(p.ISSUE_WIDTH, new uop()))  //发射的指令
    val issue_uop_valid = Output(Vec(p.ISSUE_WIDTH, Bool()))  //发射的指令的有效信号
    val value1 = Output(Vec(p.ISSUE_WIDTH, UInt(p.XLEN.W))) //发射的指令的操作数1
    val value2 = Output(Vec(p.ISSUE_WIDTH, UInt(p.XLEN.W))) //发射的指令的操作数2

    //监听PRF的valid信号用于更新ready状态
    val prf_valid = Input(Vec(p.PRF_DEPTH, Bool())) //PRF的valid信号
    //监听FU后级间寄存器内的物理寄存器ready信号
    val complete_map2 = Input(Vec(p.FU_NUM, Bool()))  //完成映射表
    val complete_uop2 = Input(Vec(p.FU_NUM, new uop()))  //来自exu的uop
    //监听FU处物理寄存器的ready信号
    val complete_map1 = Input(Vec(p.FU_NUM, Bool()))  //完成映射表
    val complete_uop1 = Input(Vec(p.FU_NUM, new uop()))  //来自exu的uop

    //输出至Dispatch Unit的信号
    val iq_freelist_update = Output(Vec(p.ISSUE_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //更新IQ Freelist
}

class exu_issue_queue extends Module {
    val io = IO(new exu_issue_IO())
}