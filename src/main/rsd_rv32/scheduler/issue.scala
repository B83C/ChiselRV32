package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

//
class Issue_Quene extends Module {
    val io = IO(new Bundle {
        val dispatch_in = Flipped(Vec(p.DISPATCH_WIDTH, DispatchedInsr))  //来自Dispatch Unit的输入
        val exu_wakeup = Flipped(Vec(p.Num_WakeupPorts, Valid(new WakeupInfo())))  //来自执行单元的唤醒信号

        val pred_wakeup = Flipped(Valid(UInt(log2Ceil(p.PRED_SIZE).W)))  //来自分支预测单元的唤醒信号
        val brupdate = Flipped(Vec(p.BRANCH_WIDTH, BranchInfo))  //来自分支预测单元的更新信号
    })
}