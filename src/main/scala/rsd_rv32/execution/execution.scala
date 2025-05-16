package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

import rsd_rv32.frontend._

class EXUIO(fu_num: UInt)(implicit p: Parameters) extends Bundle{
  //来自exu_issue queue的输入
  val execute_uop = Flipped(Vec(fu_num, Valid(new EXUISSUE_EXU_uop)))

  // val readys = Output(Vec(fu_num, Bool()))

  // val wb = Output(Vec(p.FU_NUM, Valid(new WB_uop())))
  
  // val branch_info = Output(new FUBranchInfo())
  // val rob_signal = Input(new ROBSignal())
  
  // Should be removed in place of more general readys
  //反馈给exu_issue queue的信号
  val mul_ready = Output(Bool())
  val div_ready = Output(Bool())

  // val wb_uop = Vec(fu_num, Valid(new WB_uop()))
  //写回信号
  val alu_wb_uop = Vec(p.ALU_NUM, Valid(new ALU_WB_uop()))
  val bu_wb_uop = Vec(p.BU_NUM, Valid(new BU_WB_uop()))
  val mul_wb_uop = Vec(p.MUL_NUM, Valid(new ALU_WB_uop()))
  val divrem_wb_uop = Vec(p.DIV_NUM, Valid(new ALU_WB_uop()))
}

//把exu的各个fu封装起来的顶层模块
class EXU(implicit p: Parameters) extends Module {
  val alu = Seq.fill(p.ALU_NUM)(Module(new ALUFU))
  val bu = Seq.fill(p.BU_NUM)(Module(new BranchFU))
  val mul = Seq.fill(p.MUL_NUM)(Module(new MULFU))
  val div = Seq.fill(p.DIV_NUM)(Module(new DIVFU))
  val csru = Seq.fill(p.CSRU_NUM)(Module(new CSRFU))

  val fus = (alu ++ bu ++ mul ++ div ++ csru)
  val io = IO(new EXUIO(fus.length))
  
  // io.readys := VecInit((alu ++ bu ++ mul ++ div ++ csru).map(!_.io.uop.ready))
  //TODO 改成readys
  io.mul_ready  := VecInit((mul).map(_.io.uop.ready)).orR
  io.div_ready  := VecInit((div).map(_.io.uop.ready)).orR
  def get_readys_instr_type: Seq[InstrType] = fus.map(_.supportedInstrTypes())

  // io.wb_uop := VecInit(fus.map(_.io.out))
  io.alu_wb_uop := VecInit((alu ++ csru).map(_.out))
  io.bu_wb_uop := VecInit(bu.map(_.out))
  io.mul_wb_uop := VecInit(mul.map(_.out))
  io.divrem_wb_uop := VecInit(div.map(_.out))
}


// class ALUTop(implicit p: Parameters) extends Module {
//   val io = IO(new Bundle {
//     // 来自前端的请求
//     val req = Flipped(Valid(new Bundle {
//       val rs1 = UInt(p.XLEN.W)
//       val rs2 = UInt(p.XLEN.W)
//       val uop = new EXUISSUE_EXU_uop()
//     }))

//     // 写回端口
//     val wb = Valid(new ALU_WB_uop())

//     // 其他控制信号
//     val busy = Output(Bool())
//   })

  //Branch顶层模块
  class BRTop(implicit p: Parameters) extends Module {
    val io = IO(new Bundle {
      // 来自前端的请求
      val req = Flipped(Valid(new Bundle {
        val rs1 = UInt(p.XLEN.W)
        val rs2 = UInt(p.XLEN.W)
        val pc = UInt(p.XLEN.W)
        val imm = UInt(p.XLEN.W)
        val uop = new EXUISSUE_EXU_uop()
      })

      // 写回端口
      val wb = Valid(new ALU_WB_uop())

      // 分支预测接口
      val pred = new Bundle {
        val taken = Output(Bool())
        val target = Output(UInt(p.XLEN.W))
        val mispredict = Output(Bool())
      }

      // 控制信号
      val flush = Input(Bool())
      val busy = Output(Bool())
    })

    // 实例化BR功能单元
    val br_fu = Module(new BranchFU())

    // 连接输入
    br_fu.io.req.valid := io.req.valid
    br_fu.io.req.bits.rs1 := io.req.bits.rs1
    br_fu.io.req.bits.rs2 := io.req.bits.rs2
    br_fu.io.req.bits.uop := io.req.bits.uop
    br_fu.io.req.bits.uop.instr_addr := io.req.bits.pc
    br_fu.io.req.bits.uop.imm := io.req.bits.imm
    br_fu.io.req.bits.kill := io.flush

    // 连接输出到写回端口
    io.wb.valid := br_fu.io.out.valid
    io.wb.bits := br_fu.io.out.bits

    // 分支预测接口
    io.pred.taken := br_fu.io.branch_info.get.taken
    io.pred.target := br_fu.io.branch_info.get.target
    io.pred.mispredict := br_fu.io.branch_info.get.taken =/= io.req.bits.uop.branch_pred.taken ||
      (br_fu.io.branch_info.get.taken &&
        br_fu.io.branch_info.get.target =/= io.req.bits.uop.target_PC)

    // 忙信号
    io.busy := br_fu.io.busy

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

      ALU_ADDI -> (io.in1 + io.in2),
      ALU_ANDI -> (io.in1 & io.in2),
      ALU_ORI  -> (io.in1 | io.in2),
      ALU_XORI -> (io.in1 ^ io.in2),
      ALU_SLLI -> (io.in1 << shamt),
      ALU_SRLI -> (io.in1 >> shamt),
      ALU_SRAI -> (io.in1.asSInt >> shamt).asUInt
    )
  )


}

class BRIO(implicit p: Parameters) extends Bundle {
  // 输入操作数
  val rs1 = Input(UInt(p.XLEN.W))      // 寄存器rs1值
  val rs2 = Input(UInt(p.XLEN.W))      // 寄存器rs2值
  val pc = Input(UInt(p.XLEN.W))       // 当前指令PC
  val imm = Input(UInt(p.XLEN.W))      // 符号扩展后的偏移量
  val fn = Input(UInt(4.W))            // 分支操作码（见下方常量）

  // 输出结果
  val branchTaken = Output(Bool())     // 是否跳转
  val targetPC = Output(UInt(p.XLEN.W)) // 跳转目标地址
  val pcPlus4 = Output(UInt(p.XLEN.W)) // PC+4（用于JAL/JALR写回）
  val cmp_out = Output(Bool())
}

class BR(implicit p: Parameters) extends Module with BRConsts {
  val io = IO(new BRIO)

  // 计算PC+4（用于JAL/JALR写回）
  io.pcPlus4 := io.pc + 4.U

  io.cmp_out := MuxLookup(io.fn, false.B)(
    Seq(
      BR_EQ  -> (io.rs1 === io.rs2),
      BR_NE  -> (io.rs1 =/= io.rs2),
      BR_LT  -> (io.rs1.asSInt < io.rs2.asSInt),
      BR_GE  -> (io.rs1.asSInt >= io.rs2.asSInt),
      BR_LTU -> (io.rs1 < io.rs2),
      BR_GEU -> (io.rs1 >= io.rs2),

      BR_JALR -> ((io.rs1 + io.imm) & ~1.U),
      BR_JAL  -> (io.pc + io.imm)
    )
  )

}

/*class BypassInfo(implicit p: Parameters) extends CustomBundle {
  val pdst = UInt(log2Ceil(p.PRF_DEPTH).W)
  val data = UInt(p.XLEN.W)
}

class BypassNetworkIO(
                     )(implicit p: Parameters) extends CustomBundle with HasUOP {
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

 */

class FUReq()(implicit p: Parameters) extends CustomBundle {
  val kill = Input(Bool())   //Killed upon misprediction/exception
  val rs1 = Input(UInt(p.XLEN.W))  //通过RRDWB获得的rs1数据
  val rs2 = Input(UInt(p.XLEN.W))  //通过RRDWB获得的rs1数据
}

class ExuDataOut()(implicit p: Parameters) extends CustomBundle {
  val data = UInt(p.XLEN.W)
  val uop = new WB_uop()
}

class FUBranchInfo(implicit p: Parameters) extends CustomBundle {
  val taken = Bool()
  val target = UInt(p.XLEN.W)
}

class ROBSignal(implicit p: Parameters) extends CustomBundle {
  // 添加必要的ROB信号字段
}

/*
class CSRSignals(implicit p: Parameters) extends Bundle {
  val csr_op = UInt(2.W)
  val csr_addr = UInt(12.W)
}
*/

// class ALUSignals extends Bundle {
//   val opr1_sel = UInt(3.W)
//   val opr2_sel = UInt(3.W)
//   val alu_fn = UInt(4.W)
// }

// class BranchSignals extends Bundle {
//   val opr1_sel = UInt(3.W)
//   val opr2_sel = UInt(3.W)
//   val br_fn = UInt(4.W)
//   val is_jalr = Bool()
// }

// class MULSignals extends Bundle {
//   val use_imm = Bool()
// }

// class DIVSignals extends Bundle {
//   val is_signed = Bool()
//   val use_imm = Bool()
// }

//功能单元的抽象类，定义了底层模块端口
abstract class FunctionalUnit(
)(implicit p: Parameters) extends Module {
  def supportedInstrTypes: Set[InstrType.Type]
  val io = IO(new Bundle {
    val uop = Flipped(Decoupled(new EXUISSUE_EXU_uop()))
  })
}

class ALUFU(implicit p: Parameters) extends FunctionalUnit() {
  override def supportedInstrTypes = Set(InstrType.ALU)
  // 为了配合上一级的uop，输出按FU区分
  val out = Valid(new ALU_WB_uop())
  
  val internal_alu = Module(new ALU())
  val fu_signals = io.uop.bits.fu_signals

  val is_LUI = fu_signals.opr1_sel1 === OprSel.IMM
  val is_AUIPC = fu_signals.opr1_sel1 === OprSel.PC
  
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
  
  //TODO
  internal_alu.io.fn := alu_signals.alu_fn

  val data_out = Wire(new ALU_WB_uop())  // 改为使用新的ALU_WB_uop
  data_out.pdst := io.uop.bits.pdst
  data_out.pdst_value := internal_alu.io.out
  data_out.rob_index := io.uop.bits.rob_index

  out.valid := io.uop.valid
  out.bits := data_out
  io.uop.ready := true.B
}

class BranchFU(implicit p: Parameters) extends FunctionalUnit() {
  val internal_alu = Module(new ALU())
  val br_signals = io.req.bits.uop.fu_signals.asTypeOf(new BranchSignals)

  // 操作数选择
  internal_alu.io.in1 := MuxLookup(br_signals.opr1_sel, 0.U)(Seq(
    0.U -> io.req.bits.rs1,
    1.U -> io.req.bits.uop.imm,
    2.U -> io.req.bits.uop.instr_addr,
    3.U -> 0.U
  ))

  internal_alu.io.in2 := MuxLookup(br_signals.opr2_sel, 0.U)(Seq(
    0.U -> io.req.bits.rs2,
    1.U -> io.req.bits.uop.imm,
    2.U -> io.req.bits.uop.instr_addr,
    3.U -> 0.U
  ))

  internal_alu.io.fn := br_signals.br_fn

  val data_out = Wire(new ExuDataOut())
  data_out.uop := io.req.bits.uop
  data_out.data := io.req.bits.uop.instr_addr + Mux(br_signals.is_jalr, io.req.bits.rs1, io.req.bits.uop.imm)

  // 分支判断
  val taken = internal_alu.io.cmp_out && io.req.valid

  io.out.valid := io.req.valid
  io.out.bits := data_out
  io.branch_info.get.taken := taken
  io.branch_info.get.target := Mux(br_signals.is_jalr,
    (io.req.bits.rs1 + io.req.bits.uop.imm) & ~1.U,
    io.req.bits.uop.instr_addr + io.req.bits.uop.imm)
  io.busy := false.B
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

/*
class CSRFU(implicit p: Parameters) extends FunctionalUnit(false, true) {
  val csr_signals = io.req.bits.uop.fu_signals.asTypeOf(new CSRSignals)

  // CSR读写逻辑
  val csr_addr = io.req.bits.uop.imm(11, 0)
  val csr_wdata = MuxLookup(csr_signals.csr_op, 0.U)(Seq(
    0.U -> io.req.bits.rs1,
    1.U -> (io.csr_rdata.get | io.req.bits.rs1),
    2.U -> (io.csr_rdata.get & ~io.req.bits.rs1),
    3.U -> io.req.bits.uop.imm
  ))

  val data_out = Wire(new ExuDataOut())
  data_out.uop := io.req.bits.uop
  data_out.data := io.csr_rdata.get

  io.out.valid := io.req.valid
  io.out.bits := data_out
  io.csr_wen.get := io.req.valid && csr_signals.csr_op =/= 0.U
  io.csr_waddr.get := csr_addr
  io.csr_wdata.get := csr_wdata
  io.busy := false.B
}
 */
