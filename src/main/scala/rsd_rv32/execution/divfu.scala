package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

class DIVFU(implicit p: Parameters) extends FunctionalUnit() {
  val div_signals = io.req.bits.uop.fu_signals.asTypeOf(new DIVSignals)

  // 多周期除法状态机
  val s_idle :: s_div :: s_done :: Nil = Enum(3)
  val state = RegInit(s_idle)
  val counter = RegInit(0.U(5.W))
  val result = Reg(UInt(32.W))

  // 操作数选择
  val dividend = Mux(div_signals.is_signed, io.req.bits.rs1.asSInt, io.req.bits.rs1.zext)
  val divisor = Mux(div_signals.is_signed && div_signals.use_imm,
    io.req.bits.uop.imm.asSInt,
    Mux(div_signals.is_signed,
      io.req.bits.rs2.asSInt,
      io.req.bits.rs2.zext))

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
  data_out.uop := io.req.bits.uop
  data_out.data := result

  io.out.valid := state === s_done
  io.out.bits := data_out
  io.busy := state =/= s_idle
}

