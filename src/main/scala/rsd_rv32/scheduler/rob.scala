package rsd_rv32.scheduler
import chisel3._
import chisel3.util._
import rsd_rv32.common._




class Dispatch_ROB_Interface(implicit p: Parameters) extends Bundle {
    val dis_valid = Output(Vec(p.DISPATCH_WIDTH, Bool()))  //Dispatch Unit的有效标志
    val dis_uops = Output(Vec(p.DISPATCH_WIDTH, new uop()))  //Dispatch Unit的uop
    val dis_instr_PC = Output(Vec(p.DISPATCH_WIDTH,UInt(32.W)))  //Dispatch Unit的指令PC值

    val rob_empty = Input(Bool())  //ROB空标志(0表示空，1表示非空)
    val rob_ready = Input(Bool())  //ROB准备好标志
    val rob_head = Input(UInt(log2Ceil())) //ROB头指针
    val rob_tail = Input(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB尾指针
}

class WB_ROB_Interface(implicit p: Parameters) extends Bundle {
    val complete_map = Input(Vec(p.FU_NUM, Bool()))  //完成映射表
    val complete_uop = Input(Vec(p.FU_NUM, new uop()))  //来自exu的uop
    val mispred = Input(Bool()) //分支误预测信号
    val if_jump = Input(Bool()) //分支指令跳转信号
}

class ROB_broadcast(implicit p: Parameters) extends Bundle {
    val commit_signal = Output(Vec(p.DISPATCH_WIDTH, UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))) //ROB条目
}


class ROBIO(implicit p: Parameters) extends Bundle {
    val dis_rob = Flipped(new Dispatch_ROB_Interface())
    val exu_rob = Flipped(new EXU_ROB_Interface())
    val rob_commit = new ROB_broadcast()
}
// 重命名缓冲区，主要用于存储指令的执行结果。它是一个FIFO结构，先进先出。指令在执行完成后，将结果写入ROB中。ROB中的数据可以被其他指令读取，从而实现数据的共享和重用。
class ROB(implicit p: Parameters) extends Module {
    val io = IO(new ROBIO())
}