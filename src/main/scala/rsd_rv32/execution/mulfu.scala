package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

/*class MULTop(implicit p: Parameters) extends Module {
    val io = IO(new Bundle {
      // 来自前端的请求
      val req = Flipped(Valid(new Bundle {
        val rs1 = UInt(p.XLEN.W)
        val rs2 = UInt(p.XLEN.W)
        val uop = new EXUISSUE_EXU_uop()
      }))

      // 写回端口
      val wb = Valid(new ALU_WB_uop())

      // 旁路接口
      val bypass = Output(new Bundle {
        val valid = Bool()
        val data = UInt(p.XLEN.W)
        val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
      })

      // 控制信号
      val flush = Input(Bool())
      val busy = Output(Bool())
    })

    // 实例化流水线乘法器
    val mul_fu = Module(new MULFU())

    // 连接输入
    mul_fu.io.execute_uop(0).valid := io.req.valid
    mul_fu.io.execute_uop(0).bits := io.req.bits.uop
    mul_fu.io.execute_uop(0).bits.ps1_value := io.req.bits.rs1
    mul_fu.io.execute_uop(0).bits.ps2_value := io.req.bits.rs2
    mul_fu.io.flush := io.flush

    // 连接输出到写回端口
    io.wb.valid := mul_fu.io.mul_wb_uop(0).valid
    io.wb.bits := mul_fu.io.mul_wb_uop(0).bits

    // 旁路接口
    io.bypass.valid := mul_fu.io.mul_wb_uop(0).valid
    io.bypass.data := mul_fu.io.mul_wb_uop(0).bits.pdst_value
    io.bypass.pdst := mul_fu.io.mul_wb_uop(0).bits.pdst

    // 忙信号
    io.busy := mul_fu.io.mul_ready === false.B

  }



  // 实例化ALU功能单元
  val alu_fu = Module(new ALUFU())

  // 连接输入
  alu_fu.io.req.valid := io.req.valid
  alu_fu.io.req.bits.rs1 := io.req.bits.rs1
  alu_fu.io.req.bits.rs2 := io.req.bits.rs2
  alu_fu.io.req.bits.uop := io.req.bits.uop

  // 连接输出到写回端口
  io.wb.valid := alu_fu.io.out.valid
  io.wb.bits := alu_fu.io.out.bits

  // 忙信号
  io.busy := alu_fu.io.busy
}


 */


/*
class MULFU(implicit p: Parameters) extends FunctionalUnit() {
  override def supportedInstrTypes = Set(InstrType.MUL)
  val out = Valid(new ALU_WB_uop())
  val io = IO(new EXUIO {
    val flush = Input(Bool())  // 流水线刷新信号
  })

  //------------------------------
  // 流水线寄存器定义
  //------------------------------
  class MulStage1Reg extends Bundle {
    val uop = new EXUISSUE_EXU_uop  // 保留uop信息（用于写回）
    val a_ext = UInt(64.W)          // 符号扩展后的被乘数
    val b_ext = UInt(33.W)          // Booth编码后的乘数
    val op_type = UInt(2.W)         // 乘法类型（直接从fu_signals.mul_op获取）
    val valid = Bool()
  }

  class MulStage2Reg extends Bundle {
    val uop = new EXUISSUE_EXU_uop
    val partial_sum = UInt(64.W)    // 部分积和
    val partial_carry = UInt(64.W)  // 部分积进位
    val valid = Bool()
  }

  val s1_reg = RegInit(0.U.asTypeOf(new MulStage1Reg))
  val s2_reg = RegInit(0.U.asTypeOf(new MulStage2Reg))
  val s3_reg = Reg(Valid(new ALU_WB_uop))

  //------------------------------
  // Stage 1: 操作数预处理
  //------------------------------
  val issue_fire = io.execute_uop(0).valid && io.mul_ready
  val uop = io.execute_uop(0)

  when(issue_fire && !io.flush) {
    s1_reg.uop := uop
    s1_reg.op_type := uop.fu_signals.mul_op  // 修正拼写错误
    s1_reg.a_ext := MuxLookup(s1_reg.op_type, 0.U)(Seq(
      0.U -> Cat(Fill(32, 0.U), uop.ps1_value),        // MUL: 无符号扩展
      1.U -> Cat(Fill(32, uop.ps1_value(31)), uop.ps1_value), // MULH: 有符号扩展
      2.U -> Cat(Fill(32, uop.ps1_value(31)), uop.ps1_value), // MULHSU: a有符号
      3.U -> Cat(Fill(32, 0.U), uop.ps1_value)         // MULHU: 无符号扩展
    ))
    s1_reg.b_ext := MuxLookup(s1_reg.op_type, 0.U)(Seq(
      0.U -> Cat(uop.ps2_value, 0.U(1.W)),             // MUL: b无符号
      1.U -> Cat(uop.ps2_value(31), uop.ps2_value, 0.U(1.W)), // MULH: b有符号
      2.U -> Cat(0.U(1.W), uop.ps2_value, 0.U(1.W)),   // MULHSU: b无符号
      3.U -> Cat(0.U(1.W), uop.ps2_value, 0.U(1.W))    // MULHU: b无符号
    ))
    s1_reg.valid := true.B
  }.otherwise {
    s1_reg.valid := false.B
  }

  //------------------------------
  // Stage 2: Booth部分积生成与压缩
  //------------------------------
  when(s1_reg.valid && !io.flush) {
    s2_reg.uop := s1_reg.uop
    val booth_bits = s1_reg.b_ext(2, 0)
    s2_reg.partial_sum := MuxLookup(booth_bits, 0.U(64.W))(Seq(
      "b000".U -> 0.U(64.W),
      "b001".U -> s1_reg.a_ext,
      "b010".U -> s1_reg.a_ext,
      "b011".U -> (s1_reg.a_ext << 1),
      "b100".U -> (-(s1_reg.a_ext << 1)).asUInt,
      "b101".U -> (-s1_reg.a_ext).asUInt,
      "b110".U -> (-s1_reg.a_ext).asUInt,
      "b111".U -> 0.U(64.W)
    ))
    s2_reg.partial_carry := 0.U
    s2_reg.valid := true.B
  }.otherwise {
    s2_reg.valid := false.B
  }

  //------------------------------
  // Stage 3: 最终加法与结果选择
  //------------------------------
  when(s2_reg.valid && !io.flush) {
    s3_reg.valid := true.B
    s3_reg.bits.rob_index := s2_reg.uop.rob_index
    s3_reg.bits.pdst := s2_reg.uop.pdst
    val full_result = s2_reg.partial_sum + s2_reg.partial_carry
    s3_reg.bits.pdst_value := MuxLookup(s1_reg.op_type, 0.U(p.XLEN.W))(Seq(
      0.U -> Cat(Fill(p.XLEN-32, 0.U), full_result(31, 0)),   // MUL: 低32位
      1.U -> Cat(Fill(p.XLEN-32, full_result(63)), full_result(63, 32)), // MULH: 高32位符号扩展
      2.U -> Cat(Fill(p.XLEN-32, 0.U), full_result(63, 32)),  // MULHSU: 高32位无符号
      3.U -> Cat(Fill(p.XLEN-32, 0.U), full_result(63, 32))   // MULHU: 高32位无符号
    ))
  }.otherwise {
    s3_reg.valid := false.B
  }

  //------------------------------
  // 输出连接
  //------------------------------
  io.mul_ready := !s1_reg.valid  // Stage1空闲时可接收新指令
  io.mul_wb_uop(0) := s3_reg     // 写回端口0
}

 */

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

  when(io.in.start && ~busy) {
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
        resultReg := MuxLookup(mulTypeReg, fullResult(31, 0))( Seq(
          0.U -> fullResult(31, 0),  // MUL: 取低32位
          1.U -> fullResult(63, 32),  // MULH: 取高32位(有符号×有符号)
          2.U -> fullResult(63, 32),  // MULHSU: 取高32位(有符号×无符号)
          3.U -> fullResult(63, 32)   // MULHU: 取高32位(无符号×无符号)
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