package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._
import Utils._

//功能单元的抽象类，定义了底层模块端口
class ALUFU(implicit p: Parameters) extends FunctionalUnit() {
  override def supportedInstrTypes = Set(InstrType.ALU)
  // 为了配合上一级的uop，输出按FU区分
  val out = Valid(new ALU_WB_uop())
  
  val internal_alu = Module(new ALU())
  val fu_signals = io.uop.bits.fu_signals

  val is_LUI = fu_signals.opr1_sel === OprSel.IMM
  val is_AUIPC = fu_signals.opr1_sel === OprSel.PC
  
  def Sel(sel: OprSel.Type, reg: UInt) = {
    MuxLookup(sel, 0.U)(Seq(
      OprSel.IMM -> Mux(is_LUI || is_AUIPC, immExtract(Cat(io.uop.bits.instr, 0.U(7.W)), IType.U), immExtract(Cat(io.uop.bits.instr, 0.U(7.W)), IType.I)),
      OprSel.REG -> reg,
      OprSel.PC -> io.uop.bits.instr_addr,
      OprSel.Z -> 0.U,
    ))
  }

  internal_alu.io.in1 := Sel(fu_signals.opr1_sel, io.uop.bits.ps1_value)
  internal_alu.io.in2 := Sel(fu_signals.opr2_sel, io.uop.bits.ps2_value)
  
  //TODO: Fix the alu fn 
  // internal_alu.io.fn := alu_signals.alu_fn

  val data_out = Wire(new ALU_WB_uop())  // 改为使用新的ALU_WB_uop
  data_out.pdst := io.uop.bits.pdst
  data_out.pdst_value := internal_alu.io.out
  data_out.rob_index := io.uop.bits.rob_index

  out.valid := io.uop.valid
  out.bits := data_out
  io.uop.ready := true.B
}

// ALU 的 interface
class ALUIO(implicit p: Parameters) extends Bundle {
  // 输入操作数
  val in1 = Input(UInt(p.XLEN.W))
  val in2 = Input(UInt(p.XLEN.W))
  val fn  = Input(UInt(4.W))  // ALU操作码

  // 输出结果
  val out = Output(UInt(p.XLEN.W))
  val cmp_out = Output(Bool())  // 比较结果
}

class ALU(implicit p: Parameters) extends Module with ALUConsts {
  val io = IO(new ALUIO)
  // 主ALU逻辑
  val shamt = io.in2(4,0)  // 移位量

  io.out := MuxLookup(io.fn, 0.U)(
    Seq(
      ALU_ADD  -> (io.in1 + io.in2),
      ALU_SUB  -> (io.in1 - io.in2),
      ALU_AND  -> (io.in1 & io.in2),
      ALU_OR   -> (io.in1 | io.in2),
      ALU_XOR  -> (io.in1 ^ io.in2),
      ALU_SLT  -> (io.in1.asSInt < io.in2.asSInt).asUInt,
      ALU_SLTU -> (io.in1 < io.in2),
      ALU_SLL  -> (io.in1 << shamt),
      ALU_SRL  -> (io.in1 >> shamt),
      ALU_SRA  -> (io.in1.asSInt >> shamt).asUInt,
    )
  )
}
