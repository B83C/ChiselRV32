package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

class Divider extends Module {
  val io = IO(new Bundle {
    //    val fn = Input(UInt(2.W))
    val valid     = Input (UInt(1.W))
    val dividend  = Input (UInt(16.W))
    val divisor   = Input (UInt(16.W))
    val ready     = Output(UInt(1.W))
    val quotient  = Output(UInt(16.W))
    val remainder = Output(UInt(16.W))
  })

  // Use shorter variable names
  val dividend  = io.dividend
  val divisor   = io.divisor
  val valid     = io.valid
  val quotient  = Wire(UInt (16.W))
  val remainder = Wire(UInt (16.W))

  // some default value is needed
  // quotient  := 0.U
  // remainder := 0.U

  //  quotient  := dividend / divisor
  //  remainder := dividend % divisor

  val r_ready    = RegInit(0.U(1.W))
  val r_counter  = RegInit(16.U(5.W))
  val r_dividend = RegInit(0.U(16.W))
  val r_quotient = RegInit(0.U(16.W))

  // // substract only
  //   when(valid === 1.U) {
  //     r_ready    := 0.U
  //     r_dividend := dividend
  //     r_quotient := 0.U
  //   }.elsewhen(r_dividend >= divisor){
  //     r_dividend := r_dividend - divisor
  //     r_quotient := r_quotient + 1.U
  //   }.otherwise {
  //     r_ready    := 1.U
  //   }

  // shift + substract
  when(valid === 1.U) {
    r_ready    := 0.U
    r_counter  := 16.U
    r_dividend := dividend
    r_quotient := 0.U
  }.elsewhen((r_counter!=0.U).asBool){
    when(r_dividend >= ((divisor<<(r_counter-1.U)).asUInt)){
      r_dividend    := r_dividend - ((divisor<<(r_counter-1.U)).asUInt)
      r_quotient    := r_quotient + (1.U<<(r_counter-1.U))
    }.otherwise {
    }
    r_counter  := r_counter - 1.U
    r_ready    := (r_counter === 1.U)
  }.otherwise {
  }

  remainder := r_dividend
  quotient  := r_quotient

  // Output
  io.ready     := r_ready
  io.quotient  := quotient
  io.remainder := remainder
}

class DividerTop extends Module {
  val io = IO(new Bundle {
    val valid     = Input (UInt(1.W))
    val dividend  = Input (UInt(16.W))
    val divisor   = Input (UInt(16.W))
    val quotient  = Output(UInt(16.W))
    val remainder = Output(UInt(16.W))
  })

  val div = Module(new Divider())

  // Map switches to the ALU input ports
  // div.io.fn := io.sw(1, 0)
  div.io.valid    := io.valid
  div.io.dividend := io.dividend
  div.io.divisor  := io.divisor

  // And the result to the LEDs (with 0 extension)
  io.quotient  := div.io.quotient
  io.remainder := div.io.remainder
}


class DIVFU(implicit p: Parameters) extends FunctionalUnit() {
  override def supportedInstrTypes = Set(InstrType.DIV_REM)

  val out = Valid(new ALU_WB_uop())
  val divider = Module(new Divider())

  // 状态定义
  val s_idle :: s_busy :: s_done :: Nil = Enum(3)
  val state = RegInit(s_idle)

  // 寄存器定义
  val resultReg = RegInit(0.U(p.XLEN.W))
  val op1Reg = RegInit(0.S(p.XLEN.W))
  val op2Reg = RegInit(0.S(p.XLEN.W))
  val divTypeReg = RegInit(0.U(2.W)) // 00=DIV, 01=DIVU, 10=REM, 11=REMU
  val uopReg = Reg(new EXUISSUE_EXU_uop())

  // 操作数选择逻辑 (与MULFU保持一致)
  def Sel(sel: OprSel.Type, reg: UInt) = {
    MuxLookup(sel, 0.U)(Seq(
      OprSel.IMM -> immExtract(Cat(io.uop.bits.instr, 0.U(7.W)), IType.I),
      OprSel.REG -> reg,
      OprSel.PC -> io.uop.bits.instr_addr,
      OprSel.Z -> 0.U,
    ))
  }

  // 指令解码 (从func3字段获取操作类型)
  val instr = io.uop.bits.instr
  val func3 = instr(14, 12) // R-type指令的func3字段
  val is_div    = func3 === 4.U  // DIV
  val is_divu   = func3 === 5.U  // DIVU
  val is_rem    = func3 === 6.U  // REM
  val is_remu   = func3 === 7.U  // REMU

  // 状态机转换
  switch(state) {
    is(s_idle) {
      when(io.uop.valid && (io.uop.bits.instr_type === InstrType.DIV_REM)) {
        // 锁存操作数、操作类型和uop信息
        op1Reg := Sel(io.uop.bits.fu_signals.opr1_sel, io.uop.bits.ps1_value).asSInt
        op2Reg := Sel(io.uop.bits.fu_signals.opr2_sel, io.uop.bits.ps2_value).asSInt
        divTypeReg := func3(1, 0) // 只取低2位
        uopReg := io.uop.bits

        // 设置除法器输入（根据不同类型处理符号扩展）
        val dividend = Mux(is_div || is_rem, op1Reg.abs.asUInt, op1Reg.asUInt)
        val divisor = Mux(is_div || is_rem, op2Reg.abs.asUInt, op2Reg.asUInt)

        divider.io.valid := true.B
        divider.io.dividend := dividend
        divider.io.divisor := divisor

        state := s_busy
      }
    }
    is(s_busy) {
      divider.io.valid := false.B
      when(divider.io.ready.asBool) {
        // 根据除法类型选择结果并处理符号
        val raw_quotient = divider.io.quotient
        val raw_remainder = divider.io.remainder

        // 处理有符号结果的符号位
        val quotient_sign = (op1Reg < 0.S) =/= (op2Reg < 0.S)
        val remainder_sign = op1Reg < 0.S

        val final_quotient = Mux(is_div && quotient_sign,
          (-raw_quotient.asSInt).asUInt,
          raw_quotient)
        val final_remainder = Mux(is_rem && remainder_sign,
          (-raw_remainder.asSInt).asUInt,
          raw_remainder)

        // 选择最终结果
        resultReg := MuxLookup(divTypeReg, 0.U)(Seq(
          0.U -> final_quotient,  // DIV
          1.U -> raw_quotient,    // DIVU
          2.U -> final_remainder, // REM
          3.U -> raw_remainder    // REMU
        ))

        state := s_done
      }
    }
    is(s_done) {
      state := s_idle
    }
  }

  // 输出连接
  val data_out = Wire(new ALU_WB_uop())
  data_out.pdst := uopReg.pdst
  data_out.pdst_value := resultReg
  data_out.rob_index := uopReg.rob_index

  out.valid := (state === s_done)
  out.bits := data_out

  // 流控制
  io.uop.ready := (state === s_idle)
}