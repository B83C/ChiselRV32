package rsd_rv32.execution

import chisel3._
import chisel3.util._
import rsd_rv32.common.Utils.immExtract
import rsd_rv32.common._


//功能单元的抽象类，定义了底层模块端口
class BranchFU(implicit p: Parameters) extends FunctionalUnit() {
  override def supportedInstrTypes = Set(InstrType.Branch, InstrType.Jump)
  val out = Valid(new BU_WB_uop())

  /* 分支预测结果输出接口
  val branch_info = Valid(new Bundle {
    val taken = Bool()
    val target = UInt(p.XLEN.W)
  })

   */

  // 从uop中提取控制信号
  val fu_signals = io.uop.bits.fu_signals
  val instr_type = io.uop.bits.instr_type

  // 指令类型判断
  val is_conditional = instr_type === InstrType.Branch
  val is_jal = instr_type === InstrType.Jump &&
    (fu_signals.opr1_sel === OprSel.PC)
  val is_jalr = instr_type === InstrType.Jump &&
    (fu_signals.opr1_sel === OprSel.REG)

  def Sel(sel: OprSel.Type, reg: UInt,is_jal: Boolean = false, is_jalr: Boolean = false) = {
    MuxLookup(sel, 0.U)(Seq(
      OprSel.IMM -> Mux(is_jalr.asBool, immExtract(Cat(io.uop.bits.instr, 0.U(7.W)), IType.I), Mux(is_jal.asBool,immExtract(Cat(io.uop.bits.instr, 0.U(7.W)), IType.J),immExtract(Cat(io.uop.bits.instr, 0.U(7.W)), IType.B))),
      OprSel.REG -> reg,
      OprSel.PC -> io.uop.bits.instr_addr,
      OprSel.Z -> 0.U,
    ))
  }
  // 目标地址计算
  val pc = Sel(OprSel.PC, 0.U)  // 获取当前PC值
  val rs1 = Sel(OprSel.REG, io.uop.bits.ps1_value) // 获取rs1值

  // 获取各类立即数（通过Sel函数）
  val imm_j = Sel(OprSel.IMM, 0.U, is_jal = true)    // J-type立即数
  val imm_i = Sel(OprSel.IMM, 0.U, is_jalr = true)   // I-type立即数
  val imm_b = Sel(OprSel.IMM, 0.U)                   // B-type立即数（默认）

  // 目标地址计算（使用Sel获取的操作数）
  val jal_target = pc + imm_j
  val jalr_target = (rs1 + imm_i) & ~1.U(p.XLEN.W)  // 清除最低位
  val branch_target = pc + imm_b

  val actual_target = MuxCase(0.U, Seq(
    is_jal  -> jal_target,
    is_jalr -> jalr_target,
    is_conditional -> branch_target
  ))

  // 分支方向判断
  val actual_direction = Mux(is_conditional, branch_taken, true.B)

  // 返回地址计算
  val return_addr = pc + 4.U

  // 预测错误判断
  val mispred = is_conditional &&
    (actual_direction =/= io.uop.bits.branch_pred.taken ||
      (actual_direction && actual_target =/= io.uop.bits.target_PC))

  val data_out = Wire(new BU_WB_uop())

  // 指令信息（用于调试和异常处理）
  data_out.instr := io.uop.bits.instr

  // 分支类型标识
  data_out.is_conditional := io.uop.bits.instr_type === InstrType.Branch

  // ROB写回信息
  data_out.rob_index := io.uop.bits.rob_index
  data_out.mispred := mispred // 来自之前计算的预测错误信号
  data_out.target_PC := actual_target // 计算出的实际目标地址
  data_out.branch_direction := actual_direction // 实际分支方向

  // PRF写回信息（JAL/JALR需要）
  data_out.pdst := io.uop.bits.pdst
  data_out.pdst_value := return_addr // PC+4（JAL/JALR的返回地址）

  // 输出连接
  out.valid := io.uop.valid && !io.uop.bits.kill
  out.bits := data_out
  io.uop.ready := true.B // BranchFU通常单周期完成


}


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

