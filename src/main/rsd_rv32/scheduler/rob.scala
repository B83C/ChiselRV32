package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

// 重命名缓冲区，主要用于存储指令的执行结果。它是一个FIFO结构，先进先出。指令在执行完成后，将结果写入ROB中。ROB中的数据可以被其他指令读取，从而实现数据的共享和重用。
class ROB(val num_WakeupPorts: Int)(implicit p: Parameters) extends Module {
    val io = IO(new Bundle {
        val dispatch_in = Flipped(Vec(p.DISPATCH_WIDTH, DispatchedInsr))  //来自Dispatch Unit的输入
        val branch_in = Flipped(Vec(p.BRANCH_WIDTH, BranchInfo))  //来自Branch Predictor的输入
        val exception_fetch_pc = Input(UInt(p.ADDR_WIDTH.W))  //异常发生时的PC值

        val ROB_head_idx = Output(UInt(p.ROB_ADDR_WIDTH.W))  //ROB头指针
        val ROB_tail_idx = Output(UInt(p.ROB_ADDR_WIDTH.W))  //ROB尾指针
        val ROB_pnr_idx = Output(UInt(p.ROB_ADDR_WIDTH.W))  //ROB安全上限指针

        val ROB_empty = Output(Bool())  //ROB空标志 
        val ROB_ready = Output(Bool())  //ROB准备好标志(不仅仅是非满)
        
        val WB_resps = Flipped(Vec(num_WakeupPorts, Valid(?待确认?)))  //来自执行单元的写回响应
        val LSU_clr_busy = Input(Vec(p.DISPATCH_WIDTH), Valid(UInt(p.ROB_ADDR_WIDTH.W)))  //来自LSU的清除忙标志
        val LSU_clr_unsafe = Input(Vec(p.LSU_WIDTH), Valid(UInt(p.ROB_ADDR_WIDTH.W)))  //来自LSU的清除不安全标志
        val LSU_exception = Input(Valid(new Exception()))  //来自LSU的异常标志(Exception类待定义)

        val CSR_replay = Input(Valid(new Exception()))  //来自CSR的重放标志(Exception类待定义)
        val CSR_stall = Input(Bool())  //来自CSR的停顿标志

        val commit = Output(new Commit_Info())  //提交信息(Commit_Info类待定义)
        val rollback = Output(Bool())  //回滚标志
        val commit_exception = Output(Valid(new Commit_exception_Info()))  //提交异常信息（To CSR）(Commit_exception_Info类待定义)
        
        val flush = Output(Valid(new Commit_exception_Info()))  //刷新标志(Commit_exception_Info类待定义)
        val flush_frontend = Output(Bool())  //前端刷新标志
    })
}