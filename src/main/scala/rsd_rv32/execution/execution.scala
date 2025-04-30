package rsd_rv32.execution

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

import rsd_rv32.common._

import rsd_rv32.frontend._

// ALU 的 interface
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


class BypassNetworkIO(
)(implicit p: Parameters) extends Bundle with HasUOP {
  // 寄存器读取请求
  val preg_rd    = Input(UInt(log2Ceil(p.PRF_DEPTH).W))
  
  // 输出数据
  val data_out   = Output(UInt(p.XLEN.W))
}

class BypassNetwork(
  bypassCount: Int //旁路输入宽度
)(implicit p: Parameters) extends Module {
  val io = IO(new BypassNetworkIO)
  val bypass_signals = IO(Input(Vec(bypassCount, Valid(new BypassInfo)))) //从功能单元传回的旁路输入

  io.data_out := 0.U
  
  // 检查所有旁路源
  for (i <- 0 until bypassCount) {
    when (bypass_signals(i).valid && 
          bypass_signals(i).bits.pdst === io.preg_rd) {
      io.data_out := bypass_signals(i).bits.data
    }
  }
}

//每个FunctionalUnit都能通过uop的原指令生成立即数，并且判定操作数的类型
class FUReq()(implicit p: Parameters) extends Bundle with HasUOP {
    val kill = Input(Bool())   //Killed upon misprediction/exception
    val rs1 = Input(UInt(p(XLen).W))  //通过RRDWB获得的rs1数据
    val rs2 = Input(UInt(p(XLen).W))  //通过RRDWB获得的rs1数据
}

class ExuDataOut(
)(implicit p: Parameters) extends Bundle with HasUOP {
  val data = UInt(p.XLEN.W)
}

class FUBranchInfo(implicit p: Parameters) extends Bundle {
}

class ROBSignal(implicit p: Parameters) extends Bundle {
}

//功能单元的抽象类，定义了底层模块端口
abstract class FunctionalUnit(
  needInformBranch: Boolean = false, //通知前端信息，比如BU需要提供转调信息
  needROBSignals: Boolean = false, //需要从ROB获得信息
)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val req = new FUReq() 
    val out = new DecoupledIO(new ExuDataOut())
    val rob_signal = if (needROBSignals) Input(new ROBSignal()) else null //仅有FU为CSR时才有用
    val branch_info = if (needInformBranch) Output(new FUBranchInfo()) else null //仅有FU为BU时才有用
  })
}

//未完成的ALU FU 实例
class ALUFU() extends FunctionalUnit {
  val internal_alu = new ALU()
  val alu_signals = io.req.uop.fu_signals.as_ALU
  internal_alu.io.in1 := MuxLookup(alu_signals.opr1_sel, 0)(Seq(REG -> io.req.rs1)) //TODO: fill up array and handle AUIPC, LUI, as well as PC
  internal_alu.io.in2 := MuxLookup(alu_signals.opr2_sel, 0)(Seq(REG -> io.req.rs2)) //TODO: fill up array and handle AUIPC, LUI, as well as PC

  val data_out = Wire(new ExuDataOut())
  data_out.uop := io.req.uop
  data_out.data := internal_alu.io.data_out
  
  io.out.valid := true.B
  io.out.bits := data_out
}

class BranchFU() extends FunctionalUnit(true) {
}

class MULFU() extends FunctionalUnit {
}
class DIVFU() extends FunctionalUnit {
}

class CSRFU() extends FunctionalUnit(false, true) {
}

