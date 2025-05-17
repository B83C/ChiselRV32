package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

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
      }))
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

//功能单元的抽象类，定义了底层模块端口
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

