package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

class MULFU(implicit p: Parameters) extends FunctionalUnit {
  override def supportedInstrTypes = Set(InstrType.MUL)
  val out = IO(Valid(new ALU_WB_uop()))

  val multiplier = Module(new PipelinedBoothMultiplier)
}

class MULTop(implicit p: Parameters) extends Module {
    val io = IO(new Bundle {
      // 来自前端的请求
      val req = Flipped(Valid(new Bundle {
        val rs1 = UInt(p.XLEN.W)
        val rs2 = UInt(p.XLEN.W)
        val uop = new EXUISSUE_EXU_uop()
      })

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
    val mul_fu = Module(new PipelinedBoothMultiplier())

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

/*class MULFU(implicit p: Parameters) extends FunctionalUnit() {
  val mul_signals = io.req.bits.uop.fu_signals.asTypeOf(new MULSignals)

  // 多周期乘法状态机
  val s_idle :: s_mul :: s_done :: Nil = Enum(3)
  val state = RegInit(s_idle)
  val counter = RegInit(0.U(4.W))
  val result = Reg(UInt(64.W)) // 64位结果寄存器

  // 操作数选择
  val op1 = io.req.bits.rs1
  val op2 = Mux(mul_signals.use_imm, io.req.bits.uop.imm, io.req.bits.rs2)

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
  data_out.uop := io.req.bits.uop
  data_out.data := result(31, 0) // 取低32位

  io.out.valid := state === s_done
  io.out.bits := data_out
  io.busy := state =/= s_idle
}

 */

class PipelinedBoothMultiplier(implicit p: Parameters) extends Module {
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
  val uop = io.execute_uop(0).bits

  when(issue_fire && !io.flush) {
    s1_reg.uop := uop
    s1_reg.op_type := uop.fu_signals.mul_op  // 直接使用fu_signals.mul_op
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

