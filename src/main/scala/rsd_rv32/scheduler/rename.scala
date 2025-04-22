package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

// 重命名单元将逻辑寄存器地址映射成实际寄存器。逻辑寄存器指的是ISA定义的x0-x31，而实际寄存器数量多于32个，一般可达128个。主要解决WAW，WAR等问题。
class RenameUnit_IO(implicit p: Parameters) extends Bundle {
  //with ID
  val id_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new ID_RENAME_uop()))) //来自ID单元的uop
  val ready = Output(Bool()) // 反馈给IDU，显示Rename单元是否准备好接收指令
  //with ROB
  val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W)))) //ROB提交时的广播信号，rob正常提交指令时更新amt与rmt，发生误预测时对本模块进行恢复
  //with Dispatch
  val dispatch_uop = Vec(p.CORE_WIDTH, Valid(new RENAME_DISPATCH_uop())) //发往Dispatch单元的uop
  val dispatch_ready = Input(Bool()) // 来自Dispatch单元的反馈，显示dispatch单元是否准备好接收指令
}

/*
  重命名单元采用显示重命名方式消除WAW、WAR，并保留着真实数据依赖RAW。主要维护preg_freelist，RenameMapTable(RMT)和ArchitecturMapTable。其中preg_freelist为FIFO，具有（PRF_DEPTH - 32）个表项。
推测状态重命名表RMT，从上到下依次存储x0～x31所映射到物理寄存器地址。AMT从上到下存储x0～x31所映射的处于架构状态（非推测状态）物理寄存器。
  当ID单元送来指令uop后，若含有源操作数，则从RMT中读出源操作数映射的物理寄存器地址；若含有目的寄存器，则从preg_freelist的head处读出空闲状态的物理寄存器地址，而后更新RMT；
  当指令退休时，根据rob存储的rd条目索引AMT，并把对应物理寄存器地址改为提交的物理寄存器地址，并把旧的物理寄存器地址放入preg_freelist中（freelist的tail + 1 即可）。
  Rename单元通过ROB队首的广播信息来判断是否发生误预测，换言之，误预测指令到ROB队首后才会被处理，因而RMT到恢复只需将AMT复制到RMT，并冲刷掉该模块正在处理的指令。
*/

class Rename(implicit p: Parameters) extends Module {
  val io = IO(new RenameUnit_IO())
}




