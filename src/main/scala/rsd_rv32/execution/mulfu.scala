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
  val multiplierReg = RegInit(0.U(33.W)) // One more bit
  val resultReg = RegInit(0.U(64.W))

  val shiftCounter = RegInit(0.U(8.W)) // Shift counter
  val busy = (multiplierReg =/= 0.U(33.W) && shiftCounter < 16.U(8.W))

  when(io.in.start && (~busy).asBool) {
    resultReg := 0.U(64.W)
    shiftCounter := 0.U(8.W)
    multiplicandReg := io.in.num_1.asTypeOf(SInt(64.W)).asUInt // Signed extend to 64 bit
    multiplierReg := Cat(io.in.num_2.asUInt, 0.U(1.W)) // Add one more 0 bit right next to it
  }.otherwise {
    when(busy) {
      resultReg := resultReg + MuxLookup(multiplierReg(2, 0), 0.U(64.W))( Array(
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
      shiftCounter := shiftCounter + 1.U(8.W)
    }.otherwise {
      resultReg := resultReg
      multiplicandReg := multiplicandReg
      multiplierReg := multiplierReg
      shiftCounter := shiftCounter
    }
  }

  io.out.result := resultReg.asSInt
  io.out.busy := busy
}


class MULFU(implicit p: Parameters) extends FunctionalUnit() {
  override def supportedInstrTypes = Set(InstrType.MUL)

  val out = IO(Valid(new ALU_WB_uop()))
  val boothMul = Module(new BoothMultiplier())
  // val flush = Flipped(Bool())

  // 状态定义
  val s_idle :: s_busy :: s_done :: Nil = Enum(3)
  val state = RegInit(s_idle)

  // 寄存器定义
  val resultReg = RegInit(0.U(p.XLEN.W))
  val op1Reg = RegInit(0.S(p.XLEN.W))
  val op2Reg = RegInit(0.S(p.XLEN.W))
  val mulTypeReg = RegInit(0.U(2.W)) // 00=MUL, 01=MULH, 10=MULHSU, 11=MULHU

  // 操作数选择逻辑
  def Sel(sel: OprSel.Type, reg: UInt) = {
    MuxLookup(sel, 0.U)(Seq(
      OprSel.IMM -> immExtract(Cat(io.uop.bits.instr, 0.U(7.W)), IType.I),
      OprSel.REG -> reg,
      OprSel.PC -> io.uop.bits.instr_addr,
      OprSel.Z -> 0.U,
    ))
  }
  boothMul.io.in.start := false.B
  boothMul.io.in.num_1 := 0.S(32.W)
  boothMul.io.in.num_2 := 0.S(32.W)

  // 指令解码
  val instr = io.uop.bits.instr
  // 从指令中提取func3字段(R-type指令的14-12位)
  val func3 = instr(7,5)
  // 解码乘法类型
  val is_mul    = func3 === 0.U  // MUL
  val is_mulh   = func3 === 1.U  // MULH
  val is_mulhsu = func3 === 2.U  // MULHSU
  val is_mulhu  = func3 === 3.U  // MULHU


  // 状态机转换
  switch(state) {
    is(s_idle) {
      when(io.uop.valid && io.uop.bits.instr_type === InstrType.MUL) {
        // 锁存操作数
        op1Reg := Sel(io.uop.bits.fu_signals.opr1_sel, io.uop.bits.ps1_value).asSInt
        op2Reg := Sel(io.uop.bits.fu_signals.opr2_sel, io.uop.bits.ps2_value).asSInt
        mulTypeReg := func3
        val signed1 = !is_mulhu && !is_mulhsu
        val signed2 = !is_mulhu

        // 启动乘法器
        boothMul.io.in.num_1 := Mux(signed1, op1Reg, op1Reg.asUInt.zext).asSInt
        boothMul.io.in.num_2 := Mux(signed2, op2Reg, op2Reg.asUInt.zext).asSInt
        boothMul.io.in.start := true.B  // 关键修改：明确启动信号

        state := s_busy  // 状态转移
      }.otherwise {
        boothMul.io.in.start := false.B  // 非激活状态保持start为低
      }
    }

    is(s_busy) {
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

    is(s_done) {
      state := s_idle
    }
  }

  // 输出连接
  val data_out = Wire(new ALU_WB_uop())
  data_out.pdst := io.uop.bits.pdst
  data_out.pdst_value := resultReg
  data_out.rob_index := io.uop.bits.rob_index
  data_out.instr := io.uop.bits.instr

  out.valid := (state === s_done) //&& !flush
  out.bits := data_out

  // 流控制
  io.uop.ready := (state === s_idle) //&& !flush

}
