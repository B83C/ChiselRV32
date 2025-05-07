package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

import rsd_rv32.frontend._

// ALU 的 interface
class ALUIO(implicit p: Parameters) extends Bundle {
  //输入操作数
  val in1 = Input(UInt(p.XLEN.W))  
  val in2 = Input(UInt(p.XLEN.W))  
  val fn  = Input(UInt(4.W))        

  // 输出结果
  val out = Output(UInt(p.XLEN.W)) 
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
    val rs1 = Input(UInt(p.XLEN.W))  //通过RRDWB获得的rs1数据
    val rs2 = Input(UInt(p.XLEN.W))  //通过RRDWB获得的rs1数据
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
  
  // 操作数1选择逻辑
  internal_alu.io.in1 := MuxLookup(alu_signals.opr1_sel, 0.U)(Seq(
    OPR_REG    -> io.req.rs1,
    OPR_IMM    -> io.req.uop.imm,
    OPR_PC     -> io.req.uop.pc,
    OPR_ZERO   -> 0.U,
    OPR_RS1    -> io.req.rs1
  ))
  
  // 操作数2选择逻辑
  internal_alu.io.in2 := MuxLookup(alu_signals.opr2_sel, 0.U)(Seq(
    OPR_REG    -> io.req.rs2,
    OPR_IMM    -> io.req.uop.imm,
    OPR_PC     -> io.req.uop.pc,
    OPR_ZERO   -> 0.U,
    OPR_RS2    -> io.req.rs2
  ))
  
  // ALU功能选择
  internal_alu.io.fn := alu_signals.alu_fn

  val data_out = Wire(new ExuDataOut())
  data_out.uop := io.req.uop
  data_out.data := internal_alu.io.out
  
  io.out.valid := true.B
  io.out.bits := data_out
}

class BranchFU() extends FunctionalUnit(true) {
  val internal_alu = new ALU()
  val br_signals = io.req.uop.fu_signals.as_BR
  
  // 操作数选择
  internal_alu.io.in1 := MuxLookup(br_signals.opr1_sel, 0.U)(Seq(
    OPR_REG    -> io.req.rs1,
    OPR_IMM    -> io.req.uop.imm,
    OPR_PC     -> io.req.uop.pc,
    OPR_ZERO   -> 0.U
  ))
  
  internal_alu.io.in2 := MuxLookup(br_signals.opr2_sel, 0.U)(Seq(
    OPR_REG    -> io.req.rs2,
    OPR_IMM    -> io.req.uop.imm,
    OPR_PC     -> io.req.uop.pc,
    OPR_ZERO   -> 0.U
  ))
  
  internal_alu.io.fn := br_signals.br_fn

  val data_out = Wire(new ExuDataOut())
  data_out.uop := io.req.uop
  data_out.data := io.req.uop.pc + Mux(br_signals.is_jalr, io.req.rs1, io.req.uop.imm)
  
  // 分支判断
  val taken = internal_alu.io.cmp_out && io.req.valid
  
  io.out.valid := io.req.valid
  io.out.bits := data_out
  io.br_taken := taken
  io.br_target := Mux(br_signals.is_jalr, 
                     (io.req.rs1 + io.req.uop.imm) & ~1.U, 
                     io.req.uop.pc + io.req.uop.imm)
}

class MULFU() extends FunctionalUnit {
  val mul_signals = io.req.uop.fu_signals.as_MUL
  
  // 多周期乘法状态机
  val s_idle :: s_mul :: s_done :: Nil = Enum(3)
  val state = RegInit(s_idle)
  val counter = RegInit(0.U(4.W))
  val result = Reg(UInt(64.W)) // 64位结果寄存器
  
  // 操作数选择
  val op1 = io.req.rs1
  val op2 = Mux(mul_signals.use_imm, io.req.uop.imm, io.req.rs2)
  
  // 状态机逻辑
  switch(state) {
    is(s_idle) {
      when(io.req.valid) {
        state := s_mul
        counter := 0.U
        result := 0.U
      }
    }
    is(s_mul) {
      counter := counter + 1.U
      // 简化的乘法步骤，实际应实现Booth算法等
      when(counter === 0.U) {
        result := op1 * op2
      }
      when(counter === 3.U) { state := s_done }
    }
    is(s_done) {
      state := s_idle
    }
  }
  
  val data_out = Wire(new ExuDataOut())
  data_out.uop := io.req.uop
  data_out.data := result(31, 0) // 取低32位
  
  io.out.valid := state === s_done
  io.out.bits := data_out
  io.busy := state =/= s_idle
}

class DIVFU() extends FunctionalUnit {
  val div_signals = io.req.uop.fu_signals.as_DIV
  
  // 多周期除法状态机
  val s_idle :: s_div :: s_done :: Nil = Enum(3)
  val state = RegInit(s_idle)
  val counter = RegInit(0.U(5.W))
  val result = Reg(UInt(32.W))
  
  // 操作数选择
  val dividend = Mux(div_signals.is_signed, io.req.rs1.asSInt, io.req.rs1.zext)
  val divisor = Mux(div_signals.is_signed && div_signals.use_imm, 
                   io.req.uop.imm.asSInt, 
                   Mux(div_signals.is_signed, 
                      io.req.rs2.asSInt, 
                      io.req.rs2.zext))
  
  // 状态机逻辑
  switch(state) {
    is(s_idle) {
      when(io.req.valid) {
        state := s_div
        counter := 0.U
        result := 0.U
      }
    }
    is(s_div) {
      counter := counter + 1.U
      // 简化的除法步骤，实际应实现恢复算法等
      when(divisor =/= 0.S) {
        result := (dividend / divisor).asUInt
      }
      when(counter === 4.U) { state := s_done }
    }
    is(s_done) {
      state := s_idle
    }
  }
  
  val data_out = Wire(new ExuDataOut())
  data_out.uop := io.req.uop
  data_out.data := result
  
  io.out.valid := state === s_done
  io.out.bits := data_out
  io.busy := state =/= s_idle
}

class CSRFU() extends FunctionalUnit(false, true) {
  val csr_signals = io.req.uop.fu_signals.as_CSR
  
  // CSR读写逻辑
  val csr_addr = io.req.uop.imm(11, 0)
  val csr_wdata = MuxLookup(csr_signals.csr_op, 0.U)(Seq(
    CSR_W -> io.req.rs1,
    CSR_S -> (io.csr_rdata | io.req.rs1),
    CSR_C -> (io.csr_rdata & ~io.req.rs1),
    CSR_I -> io.req.uop.imm
  ))
  
  val data_out = Wire(new ExuDataOut())
  data_out.uop := io.req.uop
  data_out.data := io.csr_rdata
  
  io.out.valid := io.req.valid
  io.out.bits := data_out
  io.csr_wen := io.req.valid && csr_signals.csr_op =/= CSR_N
  io.csr_waddr := csr_addr
  io.csr_wdata := csr_wdata
}
