package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._
import Utils._

//Github上的乘法器bundle定义
class ArithBundle extends Bundle {
  val in = Flipped(new ArithBundle_in)
  val out = (new ArithBundle_out)
}

// Flipped Bundle
class ArithBundle_in extends Bundle {
  val start = Bool() // Start signal for 1 cycle
  val num_1 = SInt(32.W) // Operation number 1
  val num_2 = SInt(32.W) // Operation number 2
  val num_1_is_signed = Bool()
  val num_2_is_signed = Bool()
}

// Output Bundle
class ArithBundle_out extends Bundle {
  val busy = Bool() // Busy status signal
  val result = SInt(64.W) // Result
}

//具体乘法器部分
class BoothMultiplier extends Module {
  val io = IO(new ArithBundle)

  val multiplicandReg = RegInit(0.U(64.W))
  val multiplierReg = RegInit(0.S(34.W)) // One more bit
  val resultReg = RegInit(0.U(64.W))

  val shiftCounter = RegInit(0.U(8.W)) // Shift counter
  val busy = (multiplierReg =/= 0.S && multiplierReg =/= ~0.S && shiftCounter < 17.U)

  when(io.in.start && (~busy).asBool) {
    resultReg := 0.U(64.W)
    shiftCounter := 0.U(8.W)
    multiplicandReg := Mux(io.in.num_1_is_signed, io.in.num_1.pad(64).asUInt, io.in.num_1.asUInt.pad(64)) // Signed extend to 64 bit
    multiplierReg := Cat(Mux(io.in.num_2_is_signed, io.in.num_2.pad(33), io.in.num_2.asUInt.zext.asSInt), 0.S(1.W)).asSInt // Add one more 0 bit right next to it
  }.otherwise {
    when(busy) {
      resultReg := resultReg + MuxLookup(multiplierReg(2, 0), 0.U(64.W))(Seq(
        "b000".U -> 0.U(64.W),
        "b001".U -> multiplicandReg,
        "b010".U -> multiplicandReg,
        "b011".U -> (multiplicandReg << 1.U).asUInt,
        "b100".U -> (-(multiplicandReg << 1.U).asUInt),
        "b101".U -> (-multiplicandReg),
        "b110".U -> (-multiplicandReg),
        "b111".U -> 0.U(64.W)
      ))
      multiplicandReg := multiplicandReg << 2.U
      multiplierReg := multiplierReg >> 2.U
      shiftCounter := shiftCounter + 1.U
    }
  }

  io.out.result := resultReg.asSInt
  io.out.busy := busy
}


class MULFU(implicit p: Parameters) extends FunctionalUnit() {
  override val properties = FUProps(
    Set(InstrType.MUL),
    bufferedInput = false,
    bufferedOutput = false
  )

  val boothMul = Module(new BoothMultiplier())

  // 状态定义
  val s_idle :: s_busy :: s_done :: Nil = Enum(3)
  val state = RegInit(s_idle)

  // 寄存器定义
  val resultReg = RegInit(0.U(p.XLEN.W))
  val op1Reg = RegInit(0.S(p.XLEN.W))
  val op2Reg = RegInit(0.S(p.XLEN.W))
  val mulTypeReg = RegInit(0.U(2.W)) // 00=MUL, 01=MULH, 10=MULHSU, 11=MULHU

  val instr = input.bits.instr_
  // 从指令中提取func3字段(R-type指令的14-12位)
  val func3 = instr(7,5)
  // 解码乘法类型
  val is_mul    = func3 === 0.U  // MUL
  val is_mulh   = func3 === 1.U  // MULH
  val is_mulhsu = func3 === 2.U  // MULHSU
  val is_mulhu  = func3 === 3.U  // MULHU
  
  // 操作数选择逻辑
  def Sel(sel: OprSel.Type, reg: UInt) = {
    MuxLookup(sel, 0.S)(Seq(
      OprSel.IMM -> immExtract(Cat(input.bits.instr_, 0.U(7.W)), IType.I),
      OprSel.REG -> reg.asSInt,
      OprSel.PC -> input.bits.instr_addr.asSInt,
      OprSel.Z -> 0.S,
    ))
  }
  boothMul.io.in.start := false.B
  boothMul.io.in.num_1 := 0.S(32.W)
  boothMul.io.in.num_2 := 0.S(32.W)
  boothMul.io.in.num_1_is_signed := is_mul || is_mulh || is_mulhsu
  boothMul.io.in.num_2_is_signed := is_mul || is_mulh

  // 指令解码

  // 状态机转换
  switch(state) {
    is(s_idle) {
      when(reset.asBool) {
        state := s_idle
      }.elsewhen(input.valid && input.bits.instr_type === InstrType.MUL) {
        // 锁存操作数
        val op1 = Sel(input.bits.opr1_sel, input.bits.ps1_value).asSInt
        val op2 = Sel(input.bits.opr2_sel, input.bits.ps2_value).asSInt

        // 仍然更新寄存器用于后续状态
        op1Reg := op1
        op2Reg := op2
        mulTypeReg := func3
        val signed1 = !is_mulhu && !is_mulhsu
        val signed2 = !is_mulhu


        // 启动乘法器
        boothMul.io.in.num_1 := Mux(signed1, op1, op1.asUInt.zext).asSInt
        boothMul.io.in.num_2 := Mux(signed2, op2, op2.asUInt.zext).asSInt
        boothMul.io.in.start := true.B  // 关键修改：明确启动信号

        state := s_busy  // 状态转移
      }.otherwise {
        boothMul.io.in.start := false.B  // 非激活状态保持start为低
      }
    }

    is(s_busy) {
      when(reset.asBool) {
        state := s_idle
        // 可以同时重置其他寄存器
        resultReg := 0.U
      }.otherwise{
      boothMul.io.in.start := false.B  // 计算期间保持start为低
      when(!boothMul.io.out.busy) {
        val fullResult = boothMul.io.out.result
        resultReg := MuxCase(fullResult(31, 0), Seq(
          is_mul    -> fullResult(31, 0),   // MUL: 取低32位
          is_mulh   -> fullResult(63, 32),  // MULH: 取高32位(有符号×有符号)
          is_mulhsu -> fullResult(63, 32),   // MULHSU: 取高32位(有符号×无符号)
          is_mulhu  -> fullResult(63, 32)    // MULHU: 取高32位(无符号×无符号)
        ))
        state := s_done
      }
      }
    }

    is(s_done) {
      when(reset.asBool) {
        state := s_idle
      }.otherwise {
        state := s_idle // 原有逻辑
      }
    }
  }

  val out_valid = (state === s_done) && !reset.asBool
  val out = Wire(new WB_uop)
  // 输出连接
  (out: Data).waiveAll :<= (input.bits: Data).waiveAll
  out.pdst_value := resultReg

  output.bits := out
  output.valid := out_valid

  // 流控制
  input.ready := !input.valid && (state === s_idle) && !reset.asBool

}

