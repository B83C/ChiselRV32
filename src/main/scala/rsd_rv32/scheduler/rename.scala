package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

// 重命名单元将逻辑寄存器地址映射成实际寄存器。逻辑寄存器指的是ISA定义的x0-x31，而实际寄存器数量多于32个，一般可达128个。主要解决WAW，WAR等问题。
class RenameUnit(
)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(p.RENAME_WIDTH, DecodedInstr))  /*  输入 */ 
    val out = Vec(p.RENAME_WIDTH, RenamedInsr)  //soaeuaoeu
  })
  io.out := io.out
}

