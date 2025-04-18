package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class Dispatch_issue_interface(implicit p: Parameters) extends Bundle {
    val dis_valid = Output(Vec(p.DISPATCH_WIDTH, Bool()))  //来自Dispatch Unit的有效信号
    val dis_uop = Output(Vec(p.DISPATCH_WIDTH, new uop()))  //来自Dispatch Unit的输入
}

class issue_exu_interface(implicit p: Parameters) extends Bundle {
    val dst_FU = Output(Vec(p.ISSUE_WIDTH, UInt(log2Ceil(p.FU_NUM).W)))  //发射的指令的目标功能单元
    val issue_uop = Output(Vec(p.ISSUE_WIDTH, new uop()))  //发射的指令
    val issue_uop_valid = Output(Vec(p.ISSUE_WIDTH, Bool()))  //发射的指令的有效信号
}

class issue_lsu_interface(implicit p: Parameters) extends Bundle {
    val dst_FU = Output(Vec(p.ISSUE_WIDTH, UInt(log2Ceil(p.FU_NUM).W)))  //发射的指令的目标功能单元
    val issue_uop = Output(Vec(p.ISSUE_WIDTH, new uop()))  //发射的指令
    val issue_uop_valid = Output(Vec(p.ISSUE_WIDTH, Bool()))  //发射的指令的有效信号
}

class iq_freelist_update(implicit p: Parameters) extends Bundle {
    val iq_freelist_id = Input(Vec(p.DISPATCH_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //IQ Freelist ID
}
// IssueQueue是一个发射队列，用于存储待发射的指令。它根据指令的优先级和状态，决定哪些指令可以被发射。
class Issue_Queue extends Module {
    val io = IO(new Bundle {
        
        val issue_uops = Output(Vec(p.ISSUE_WIDTH, IssueInsr))  //发射的指令
        val exu_wakeup = Flipped(Vec(p.Num_WakeupPorts, Valid(new WakeupInfo())))  //来自执行单元的唤醒信号

        val pred_wakeup = Flipped(Valid(UInt(log2Ceil(p.PRED_SIZE).W)))  //来自分支预测单元的唤醒信号
        val brupdate = Flipped(Vec(p.BRANCH_WIDTH, BranchInfo))  //来自分支预测单元的更新信号

        val alu_busys = Input(UInt(aluWidth.W)) //ALU单元的忙信号
        val flush = Input(Bool())  //刷新信号

        val ROB_head_idx = Input(UInt(p.ROB_ADDR_WIDTH.W)) //ROB头指针
        val ROB_pnr_idx = Input(UInt(p.ROB_ADDR_WIDTH.W))  //ROB安全上限指针


    })
}

// PayloadRAM是一个RAM模块，用于存储指令的有效载荷。
class PayloadRAM(implicit p: Parameters) extends Module {
    val io = IO(new Bundle {
        val we = Input(Vec(p.DISPATCH_WIDTH, Bool()))  //写使能信号
        val waddr = Input(Vec(p.DISPATCH_WIDTH, UInt(log2Ceil(NUM_PayloadRAM).W)))  //写地址
        val wdata = Input(Vec(p.DISPATCH_WIDTH, UInt(Insr_WIDTH.W)))  //写数据
        val raddr = Input(Vec(p.ISSUE_WIDTH, UInt(log2Ceil(NUM_PayloadRAM).W)))  //读地址
        val rdata = Output(Vec(p.ISSUE_WIDTH, UInt(Insr_WIDTH.W)))  //读数据
    })
}

// WakeupLogic是一个唤醒逻辑模块，用于处理指令的唤醒信号。它根据指令的依赖关系和状态，决定哪些指令可以被唤醒。
class WakeupLogic(implicit p: Parameters) extends Module {
    val io = IO(new Bundle {
        val stall = Input(Bool()) //停顿信号
        val write = Input(Vec(p.DISPATCH_WIDTH, Bool())) //写使能
        val writePtr = Input(Vec(p.DISPATCH_WIDTH, UInt(p.ISSUE_QUEUE_INDEX_WIDTH.W))) //写指针
        val writeSrcTag = Input(Vec(p.DISPATCH_WIDTH, new SrcTag())) //写源标签,SrcTag可能包含寄存器标签与寄存器地址
        val writeDstTag = Input(Vec(p.DISPATCH_WIDTH, new DstTag())) //写目标标签,DstTag可能包含寄存器标签
        val wakeup = Input(Vec(p.WAKEUP_WIDTH, Bool())) //唤醒信号
        val wakeupDstTag = Input(Vec(p.WAKEUP_WIDTH, new DstTag())) //唤醒目标标签
        val wakeupVector = Input(Vec(p.WAKEUP_WIDTH + p.ISSUE_STORE_WIDTH, Vec(p.ISSUE_QUEUE_ENTRY_NUM, Bool()))) //唤醒向量
        val notIssued = Input(Vec(p.ISSUE_QUEUE_ENTRY_NUM, Bool())) //未发射标志
        val dispatchStore = Input(Vec(p.DISPATCH_WIDTH, Bool())) //是否是Store
        val dispatchLoad = Input(Vec(p.DISPATCH_WIDTH, Bool())) //是否是Load
        val memDependencyPred = Input(Vec(p.DISPATCH_WIDTH, Bool())) //内存依赖预测
        val opReady = Output(Vec(p.ISSUE_QUEUE_ENTRY_NUM, Bool())) //操作就绪标志
    })
}

// SelectLogic是一个选择逻辑模块，用于选择要发射的指令。它根据指令的优先级和状态，决定哪些指令可以被发射。
class SelectLogic extends Module {
    val io = IO(new Bundle {
        val opReady = Input(Vec(p.ISSUE_QUEUE_ENTRY_NUM, Bool())) //操作就绪标志
        val intissueReq = Input(Vec(p.ISSUE_QUEUE_ENTRY_NUM, Bool())) //发射请求

        val selected = Output(Vec(p.ISSUE_WIDTH, Bool())) //选择的指令
        val selectedPtr = Output(Vec(p.ISSUE_WIDTH, UInt(p.ISSUE_QUEUE_INDEX_WIDTH.W))) //选择的指令指针
    })
}

