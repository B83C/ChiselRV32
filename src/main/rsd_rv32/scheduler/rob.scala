package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

// 重命名缓冲区，主要用于存储指令的执行结果。它是一个FIFO结构，先进先出。指令在执行完成后，将结果写入ROB中。ROB中的数据可以被其他指令读取，从而实现数据的共享和重用。
class ROB(implicit p: Parameters) extends Module {

}