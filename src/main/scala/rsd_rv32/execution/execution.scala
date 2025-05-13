package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

import rsd_rv32.frontend._

class EXUIO(implicit p: Parameters) extends Bundle{
  //来自exu_issue queue的输入
  val exu_issue_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new EXUISSUE_EXU_uop)))
  //反馈给exu_issue queue的信号
  val mul_ready = Output(Bool())
  val div_ready = Output(Bool())

  //写回信号
  val alu_wb_uop = Vec(p.ALU_NUM, Valid(new ALU_WB_uop()))
  val bu_wb_uop = Vec(p.BU_NUM, Valid(new BU_WB_uop()))
  val mul_wb_uop = Vec(p.MUL_NUM, Valid(new ALU_WB_uop()))
  val divrem_wb_uop = Vec(p.DIV_NUM, Valid(new ALU_WB_uop()))
}

//把exu的各个fu封装起来的顶层模块
class EXU(implicit p: Parameters) extends Module{
  val io = IO(new EXUIO())

  val in = Flipped(Decoupled(new EXUISSUE_EXU_uop))
  val out = Decoupled(new ExuDataOut)
  val branch_info = Output(new FUBranchInfo())
  val rob_signal = Input(new ROBSignal())

}
class ALUTop(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    // 来自前端的请求
    val req = Flipped(Valid(new Bundle {
      val rs1 = UInt(p.XLEN.W)
      val rs2 = UInt(p.XLEN.W)
      val uop = new EXUISSUE_EXU_uop()
    }))

    // 写回端口
    val wb = Valid(new ALU_WB_uop())

    // 其他控制信号
    val busy = Output(Bool())
  })

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
      BR_JAL  -> (io.pc + io.imm),           // JAL：PC + offset
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

class FUReq()(implicit p: Parameters) extends CustomBundle with HasUOP {
  val kill = Input(Bool())   //Killed upon misprediction/exception
  val rs1 = Input(UInt(p.XLEN.W))  //通过RRDWB获得的rs1数据
  val rs2 = Input(UInt(p.XLEN.W))  //通过RRDWB获得的rs1数据
}

class ExuDataOut(
                )(implicit p: Parameters) extends CustomBundle with HasUOP {
  val data = UInt(p.XLEN.W)
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

class ALUSignals extends Bundle {
  val opr1_sel = UInt(3.W)
  val opr2_sel = UInt(3.W)
  val alu_fn = UInt(4.W)
}

class BranchSignals extends Bundle {
  val opr1_sel = UInt(3.W)
  val opr2_sel = UInt(3.W)
  val br_fn = UInt(4.W)
  val is_jalr = Bool()
}

class MULSignals extends Bundle {
  val use_imm = Bool()
}

class DIVSignals extends Bundle {
  val is_signed = Bool()
  val use_imm = Bool()
}

//功能单元的抽象类，定义了底层模块端口
abstract class FunctionalUnit(
                               needInformBranch: Boolean = false, //通知前端信息，比如BU需要提供转调信息
                               needROBSignals: Boolean = false, //需要从ROB获得信息
                             )(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Valid(new FUReq()))
    val out = Decoupled(new ExuDataOut())
    val rob_signal = if (needROBSignals) Some(Input(new ROBSignal())) else None
    val branch_info = if (needInformBranch) Some(Output(new FUBranchInfo())) else None
    val busy = Output(Bool())

    /*CSR专用信号
    val csr_rdata = if (this.isInstanceOf[CSRFU]) Some(Input(UInt(p.XLEN.W))) else None
    val csr_wen = if (this.isInstanceOf[CSRFU]) Some(Output(Bool())) else None
    val csr_waddr = if (this.isInstanceOf[CSRFU]) Some(Output(UInt(12.W))) else None
    val csr_wdata = if (this.isInstanceOf[CSRFU]) Some(Output(UInt(p.XLEN.W))) else None
    */
  })
}

class ALUFU(implicit p: Parameters) extends FunctionalUnit() {
  val internal_alu = Module(new ALU())
  val alu_signals = io.req.bits.uop.fu_signals.asTypeOf(new ALUSignals)

  // 操作数1选择逻辑
  internal_alu.io.in1 := MuxLookup(alu_signals.opr1_sel, 0.U)(Seq(
    0.U -> io.req.bits.rs1,
    1.U -> io.req.bits.uop.imm,
    2.U -> io.req.bits.uop.instr_addr,
    3.U -> 0.U,
    4.U -> io.req.bits.rs1
  ))

  // 操作数2选择逻辑
  internal_alu.io.in2 := MuxLookup(alu_signals.opr2_sel, 0.U)(Seq(
    0.U -> io.req.bits.rs2,
    1.U -> io.req.bits.uop.imm,
    2.U -> io.req.bits.uop.instr_addr,
    3.U -> 0.U,
    4.U -> io.req.bits.rs2
  ))

  // ALU功能选择
  internal_alu.io.fn := alu_signals.alu_fn

  val data_out = Wire(new ALU_WB_uop())  // 改为使用新的ALU_WB_uop
  data_out.pdst := io.req.bits.uop.pdst
  data_out.pdst_value := internal_alu.io.out
  data_out.rob_index := io.req.bits.uop.rob_index

  io.out.valid := io.req.valid
  io.out.bits := data_out
  io.busy := false.B
}

class BranchFU(implicit p: Parameters) extends FunctionalUnit(true) {
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

class MULFU(implicit p: Parameters) extends FunctionalUnit() {
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
