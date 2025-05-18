package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._
// class MULTop(implicit p: Parameters) extends Module {
//     val io = IO(new Bundle {
//       // 来自前端的请求
//       val req = Flipped(Valid(new Bundle {
//         val rs1 = UInt(p.XLEN.W)
//         val rs2 = UInt(p.XLEN.W)
//         val uop = new EXUISSUE_EXU_uop()
//       })

//       // 写回端口
//       val wb = Valid(new ALU_WB_uop())

//       // 旁路接口
//       val bypass = Output(new Bundle {
//         val valid = Bool()
//         val data = UInt(p.XLEN.W)
//         val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
//       })

//       // 控制信号
//       val flush = Input(Bool())
//       val busy = Output(Bool())
//     })

//     // 实例化流水线乘法器
//     val mul_fu = Module(new PipelinedBoothMultiplier())

//     // 连接输入
//     mul_fu.io.execute_uop(0).valid := io.req.valid
//     mul_fu.io.execute_uop(0).bits := io.req.bits.uop
//     mul_fu.io.execute_uop(0).bits.ps1_value := io.req.bits.rs1
//     mul_fu.io.execute_uop(0).bits.ps2_value := io.req.bits.rs2
//     mul_fu.io.flush := io.flush

//     // 连接输出到写回端口
//     io.wb.valid := mul_fu.io.mul_wb_uop(0).valid
//     io.wb.bits := mul_fu.io.mul_wb_uop(0).bits

//     // 旁路接口
//     io.bypass.valid := mul_fu.io.mul_wb_uop(0).valid
//     io.bypass.data := mul_fu.io.mul_wb_uop(0).bits.pdst_value
//     io.bypass.pdst := mul_fu.io.mul_wb_uop(0).bits.pdst

//     // 忙信号
//     io.busy := mul_fu.io.mul_ready === false.B


//Github上的乘法器bundle定义
class ArithBundle extends Bundle {
  val in = Input(new ArithBundle_in)
  val out = Output(new ArithBundle_out)
}

// Input Bundle
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
        "b011".U -> (multiplicandReg << 1.U),
        "b100".U -> (-(multiplicandReg << 1.U)),
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

  val out = Valid(new ALU_WB_uop())
  val boothMul = Module(new BoothMultiplier())

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

  // 指令解码
  val instr = io.uop.bits.instr
  // 从指令中提取func3字段(R-type指令的14-12位)
  val func3 = instr(14, 12)
  // 解码乘法类型
  val is_mul    = func3 === 0.U  // MUL
  val is_mulh   = func3 === 1.U  // MULH
  val is_mulhsu = func3 === 2.U  // MULHSU
  val is_mulhu  = func3 === 3.U  // MULHU


  // 状态机转换
  switch(state) {
    is(s_idle) {
      when(io.uop.valid && io.uop.bits.instr_type === InstrType.MUL) {
        // 锁存操作数和操作类型
        op1Reg := Sel(io.uop.bits.fu_signals.opr1_sel, io.uop.bits.ps1_value).asSInt
        op2Reg := Sel(io.uop.bits.fu_signals.opr2_sel, io.uop.bits.ps2_value).asSInt
        mulTypeReg := func3(1, 0)

        // 设置乘法器输入（根据不同类型处理符号扩展）
        val signed1 = !is_mulhu && !is_mulhsu
        val signed2 = !is_mulhu

        boothMul.io.in.num_1 := Mux(signed1, op1Reg, op1Reg.asUInt).asSInt
        boothMul.io.in.num_2 := Mux(signed2, op2Reg, op2Reg.asUInt).asSInt
        boothMul.io.in.start := true.B

        state := s_busy
      }
    }
    is(s_busy) {
      boothMul.io.in.start := false.B
      when(!boothMul.io.out.busy) {
        // 根据乘法类型选择结果
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

  out.valid := (state === s_done)
  out.bits := data_out

  // 流控制
  io.uop.ready := (state === s_idle)

}
