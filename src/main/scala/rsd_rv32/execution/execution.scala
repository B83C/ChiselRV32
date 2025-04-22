package rsd_rv32.execution

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

import rsd_rv32.common._

import rsd_rv32.frontend._

class ALUIO(implicit p: Parameters) extends Bundle {
  //输入操作数
  val in1 = Input(UInt(p(XLen).W))  
  val in2 = Input(UInt(p(XLen).W))  
  val fn  = Input(UInt(4.W))        

  // 输出结果
  val out = Output(UInt(p(XLen).W)) 
  val cmp_out = Output(Bool())      
}

class ALU(implicit p: Parameters) extends Module {
  val io = IO(new ALUIO)

  //运算逻辑
  io.out := MuxLookup(io.fn, 0.U, Seq(
    ALU_ADD -> (io.in1 + io.in2),
    ALU_SUB -> (io.in1 - io.in2),
    ALU_AND -> (io.in1 & io.in2),
    ALU_OR  -> (io.in1 | io.in2),
    ALU_XOR -> (io.in1 ^ io.in2)
  ))

  //比较逻辑
  io.cmp_out := MuxLookup(io.fn, false.B, Seq(
    ALU_EQ  -> (io.in1 === io.in2),
    ALU_NE  -> (io.in1 =/= io.in2),
    ALU_LT  -> (io.in1.asSInt < io.in2.asSInt),
    ALU_GE  -> (io.in1.asSInt >= io.in2.asSInt)
  ))
}


class BypassNetworkIO(implicit p: Parameters) extends Bundle {
  // 来自执行单元的旁路输入
  val exec_units = Input(Vec(2, Valid(new BypassInfo)))
  
  // 寄存器读取请求
  val preg_rd    = Input(UInt(p(PhysRegIdxSz).W))
  
  // 输出数据
  val data_out   = Output(UInt(p(XLen).W))
}

class BypassNetwork(implicit p: Parameters) extends Module {
  val io = IO(new BypassNetworkIO)

  io.data_out := 0.U
  
  // 检查所有旁路源
  for (i <- 0 until 2) {
    when (io.exec_units(i).valid && 
          io.exec_units(i).bits.pdst === io.preg_rd) {
      io.data_out := io.exec_units(i).bits.data
    }
  }
}

abstract class ExecutionUnit(implicit p: Parameters) extends Module {
  val base_io = IO(new Bundle {
    val kill = Input(Bool())   //Killed upon misprediction/exception
    val branch_update = Input(new BrUpdateInfo) 
    val issued_uop = Input(Valid(new uop()))
  })
}

class ALUExu() extends ExecutionUnit {
  
}
class BranchExu() extends ExecutionUnit {
  
}
class MULExu() extends ExecutionUnit {
  
}
class CSRExu() extends ExecutionUnit {
  
}

class () extends ExecutionUnit {
  
}
