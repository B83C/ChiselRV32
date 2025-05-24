package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._
import Utils._

class Divider extends Module {
  val io = IO(new Bundle {
    val valid     = Flipped(Bool())  // 改为Bool类型更规范
    val dividend  = Flipped(UInt(32.W))  // 32位被除数
    val divisor   = Flipped(UInt(32.W))  // 32位除数
    val ready     = (Bool())
    val quotient  = (UInt(32.W))
    val remainder = (UInt(32.W))
  })

  // 寄存器定义
  val r_ready    = RegInit(false.B)
  val r_counter  = RegInit(32.U(6.W))  // 计数器扩展到6位(0-63)
  val r_dividend = RegInit(0.U(64.W))  // 扩展为64位用于中间计算
  val r_quotient = RegInit(0.U(32.W))
  val r_divisor  = RegInit(0.U(32.W))

  // 移位减法算法
  when(io.valid) {
    r_ready    := false.B
    r_counter  := 32.U
    r_dividend := Cat(0.U(32.W), io.dividend)  // 被除数左移32位
    r_divisor  := io.divisor
    r_quotient := 0.U
  }.elsewhen(r_counter =/= 0.U) {
    val diff = r_dividend - (r_divisor << (r_counter - 1.U)).asUInt

    when(!diff(63)) {  // 检查是否无借位
      r_dividend := diff
      r_quotient := r_quotient | (1.U << (r_counter - 1.U)).asUInt
    }

    r_counter := r_counter - 1.U
    r_ready   := (r_counter === 1.U)
  }

  // 输出
  io.ready     := r_ready
  io.quotient  := r_quotient
  io.remainder := r_dividend(31, 0)  // 取低32位作为余数
}


class DIVFU(implicit p: Parameters) extends FunctionalUnit() {
  override def supportedInstrTypes = Set(InstrType.DIV_REM)

  val divider = Module(new Divider())

  // 状态定义
  val s_idle :: s_busy :: s_done :: Nil = Enum(3)
  val state = RegInit(s_idle)

  // 寄存器定义
  val resultReg = RegInit(0.U(p.XLEN.W))
  val op1Reg = RegInit(0.S(p.XLEN.W))
  val op2Reg = RegInit(0.S(p.XLEN.W))

  //val divTypeReg = RegInit(0.U(2.W))
  val uopReg = RegInit(0.U.asTypeOf(new EXUISSUE_EXU_uop()))


  // 操作数选择逻辑
  def Sel(sel: OprSel.Type, reg: UInt) = {
    MuxLookup(sel, 0.U)(Seq(
      OprSel.IMM -> immExtract(Cat(io.uop.bits.instr_, 0.U(7.W)), IType.I),
      OprSel.REG -> reg,
      OprSel.PC -> io.uop.bits.instr_addr,
      OprSel.Z -> 0.U,
    ))
  }

  // 指令解码 (从func3字段获取操作类型)
  val instr = io.uop.bits.instr_
  val func3 = instr(7,5) // R-type指令的func3字段
  val is_div    = func3 === 4.U  // DIV
  val is_divu   = func3 === 5.U  // DIVU
  val is_rem    = func3 === 6.U  // REM
  val is_remu   = func3 === 7.U  // REMU

  divider.io.valid := false.B
  divider.io.dividend := 1.U
  divider.io.divisor := 1.U

  val quotient_sign = RegInit(false.B)
  val remainder_sign = RegInit(false.B)
  // 状态机转换
  switch(state) {
    is(s_idle) {
      when(io.uop.valid && (io.uop.bits.instr_type === InstrType.DIV_REM)) {
        // 锁存操作数、操作类型和uop信息
        val op1 = Sel(io.uop.bits.fu_signals.opr1_sel, io.uop.bits.ps1_value).asSInt
        val op2 = Sel(io.uop.bits.fu_signals.opr2_sel, io.uop.bits.ps2_value).asSInt

        // 仍然更新寄存器用于后续状态
        op1Reg := op1
        op2Reg := op2
        uopReg := io.uop.bits
//
        // 设置除法器输入（根据不同类型处理符号扩展）
//        val dividend = Mux(is_div || is_rem, op1Reg.abs.asUInt, op1Reg.asUInt)
//        val divisor = Mux(is_div || is_rem, op2Reg.abs.asUInt, op2Reg.asUInt)


//        state := s_busy
        val dividend = Mux(is_div || is_rem,
          Mux(op1 === Int.MinValue.S, Int.MinValue.S.abs.asUInt, op1.abs.asUInt),
          op1.asUInt)

        val divisor = Mux(is_div || is_rem,
          Mux(op2 === Int.MinValue.S, Int.MinValue.S.abs.asUInt, op2.abs.asUInt),
          op2.asUInt)

        quotient_sign := (op1 < 0.S) =/= (op2 < 0.S)
        remainder_sign := op1 < 0.S
        divider.io.valid := true.B
        divider.io.dividend := dividend
        divider.io.divisor := divisor

        // 处理除零和溢出情况
        when(op2 === 0.S) {
          // 除零处理
          resultReg := Mux(is_div || is_divu,
            "hffffffff".U,  // 商为全1
            op1.asUInt)   // 余数为被除数
          state := s_done
        }.elsewhen(is_div && (op1 === Int.MinValue.S) && (op2 === -1.S)) {
          // 有符号除法溢出处理
          resultReg := Mux(is_div,
            Int.MinValue.S.asUInt,  // -2^31
            op1.asUInt + op2.asUInt) // 余数
          state := s_done
        }.otherwise {
          // 正常除法
          divider.io.valid := true.B
          divider.io.dividend := dividend
          divider.io.divisor := divisor
          state := s_busy
        }
      }
    }
    is(s_busy) {
      divider.io.valid := false.B
      when(divider.io.ready.asBool) {
        // 根据除法类型选择结果并处理符号
        val raw_quotient = divider.io.quotient
        val raw_remainder = divider.io.remainder

        // 处理有符号结果的符号位


        val final_quotient = Mux(is_div,
          Mux(quotient_sign,
            (-raw_quotient.asSInt).asUInt,
            raw_quotient),
          raw_quotient)

        val final_remainder = Mux(is_rem,
          Mux(remainder_sign,
            (-raw_remainder.asSInt).asUInt,
            raw_remainder),
          raw_remainder)

        // 选择最终结果
        resultReg := MuxLookup(func3, 4.U)(Seq(
          4.U -> final_quotient,  // DIV
          5.U -> raw_quotient,    // DIVU
          6.U -> final_remainder, // REM
          7.U -> raw_remainder    // REMU
        ))

        state := s_done
      }
    }
    is(s_done) {
      state := s_idle
    }
  }

  val out_valid = (state === s_done)
  val out = Wire(new WB_uop)
  // 输出连接
  (out: Data).waiveAll :<= (uopReg: Data).waiveAll
  out.pdst_value.valid := true.B
  out.pdst_value.bits := resultReg

  io.out.bits := RegEnable(out, out_valid)
  io.out.valid := RegNext(out_valid)

  // 流控制
  io.uop.ready := (state === s_idle)
  
  // Debugging
  out.debug(io.uop.bits, out_valid)
}
