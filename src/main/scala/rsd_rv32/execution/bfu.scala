package rsd_rv32.execution

import chisel3._
import chisel3.util._
import rsd_rv32.common.Utils.immExtract
import rsd_rv32.common._


//功能单元的抽象类，定义了底层模块端口
class BranchFU(implicit p: Parameters) extends FunctionalUnit() with BRConsts {
  override val properties = FUProps(
    Set(InstrType.Branch, InstrType.Jump),
    bufferedInput = true,
    bufferedOutput = false
  )
  
  val bu_out = IO(Valid(new BU_uop))

  // 从uop中提取控制信号
  val instr_type = input.bits.instr_type

  // 指令类型判断
  val is_conditional = input.bits.pdst.valid
  val is_jal = instr_type === InstrType.Jump &&
    (input.bits.opr1_sel === OprSel.PC)
  val is_jalr = instr_type === InstrType.Jump &&
    (input.bits.opr1_sel === OprSel.REG)

  def Sel(sel: OprSel.Type, reg: UInt,is_jal: Boolean = false, is_jalr: Boolean = false) = {
    MuxLookup(sel, 0.S)(Seq(
      OprSel.IMM -> Mux(is_jalr.asBool, immExtract(Cat(input.bits.instr_, 0.U(7.W)), IType.I), Mux(is_jal.asBool, immExtract(Cat(input.bits.instr_, 0.U(7.W)), IType.J),immExtract(Cat(input.bits.instr_, 0.U(7.W)), IType.B))),
      OprSel.REG -> reg.asSInt,
      OprSel.PC -> input.bits.instr_addr.asSInt,
      OprSel.Z -> 0.S(32.W),
    ))
  }
  // 目标地址计算
  val pc = Sel(OprSel.PC, 0.U)  // 获取当前PC值

  // 获取各类立即数（通过Sel函数）
  val imm_j = Sel(OprSel.IMM, 0.U, is_jal = true)    // J-type立即数
  val imm_i = Sel(OprSel.IMM, 0.U, is_jalr = true)   // I-type立即数
  val imm_b = Sel(OprSel.IMM, 0.U)                   // B-type立即数（默认）

  // 分支方向判断
  val actual_taken = Wire(Bool())
  val func3 = Wire(UInt(3.W))
  func3 := input.bits.instr_(7, 5)
  val temp = MuxLookup(func3, false.B)(
    Seq(
      "b000".U  -> (rs1 === rs2),
      "b001".U  -> (rs1 =/= rs2),
      "b100".U  -> (rs1.asSInt < rs2.asSInt),
      "b101".U  -> (rs1.asSInt >= rs2.asSInt),
      "b110".U -> (rs1 < rs2),
      "b111".U -> (rs1 >= rs2),
    )
  )
  actual_taken := Mux(input.valid, Mux(is_jal || is_jalr, true.B, temp), false.B)

  // 目标地址计算（使用Sel获取的操作数）
  val jal_target = pc + imm_j
  val jalr_target = Cat((rs1 + imm_i)(p.XLEN - 1, 1), 0.U)
  //val jalr_target = ((rs1 + imm_i) & ~1.U(p.XLEN.W))// 清除最低位

  val branch_target = pc + imm_b

  val actual_target = MuxCase(0.U, Seq(
    is_jal  -> jal_target.asUInt,
    is_jalr -> jalr_target.asUInt,
    is_conditional -> branch_target.asUInt
  ))



  // 获取比较操作数（通过Sel函数）
  lazy val ps1 = input.bits.ps1_value
  lazy val ps2 = input.bits.ps2_value
  lazy val rs1 = Sel(OprSel.REG, ps1)
  lazy val rs2 = Sel(OprSel.REG, ps2)

//  val actual_direction = Mux(is_conditional, br.io.cmp_out, true.B)

  // 返回地址计算
  val return_addr = pc.asUInt + 4.U

  // 预测错误判断
  //val mispred = is_conditional && (actual_taken=/= input.bits.branch_pred || ((actual_taken=== BranchPred.T) && actual_target =/= input.bits.target_PC))
  val mispred = actual_taken =/= io.uop.bits.branch_taken || ((actual_taken) && (io.uop.bits.branch_taken) && actual_target =/= io.uop.bits.predicted_next_pc)

  // data_out.instr := input.bits.instr

  val bu_uop_out = Wire(new BU_uop)
  val out = Wire(new WB_uop)
  
  // ROB写回信息
  (bu_uop_out: Data).waiveAll :<= (input.bits: Data).waiveAll
  bu_uop_out.is_conditional := input.bits.instr_type === InstrType.Branch
  bu_uop_out.mispred := mispred // 来自之前计算的预测错误信号
  bu_uop_out.target_PC := actual_target // 计算出的实际目标地址
  bu_uop_out.branch_taken := actual_taken// 实际分支方向

  // PRF写回信息（JAL/JALR需要）
  out.pdst_value := return_addr // PC+4（JAL/JALR的返回地址）
  (out: Data).waiveAll :<= (input.bits: Data).waiveAll
  // Overwrites
  // out.pdst_value.valid := input.bits.instr_type =/= InstrType.Branch // 当指令为jump类型时，写回有效

  // 输出连接
  output.valid := input.valid
  bu_out.valid := input.valid
  
  output.bits := out
  bu_out.bits := bu_uop_out
  
  input.ready := true.B // BranchFU通常单周期完成

  // Debugging
  out.debug := input.bits.debug
}


  /*Branch顶层模块
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
    io.wb.valid := br_fu.io.output.valid
    io.wb.bits := br_fu.io.output.bits

    // 分支预测接口
    io.pred.taken := br_fu.io.branch_info.get.taken
    io.pred.target := br_fu.io.branch_info.get.target
    io.pred.mispredict := br_fu.io.branch_info.get.taken =/= io.req.bits.uop.branch_pred.taken ||
      (br_fu.io.branch_info.get.taken &&
        br_fu.io.branch_info.get.target =/= io.req.bits.uop.target_PC)

    // 忙信号
    io.busy := br_fu.io.busy

  }

   */

//class BRIO(implicit p: Parameters) extends Bundle {
//  // 输入操作数
//  val rs1 = Input(UInt(p.XLEN.W))      // 寄存器rs1值
//  val rs2 = Input(UInt(p.XLEN.W))      // 寄存器rs2值
//  val pc = Input(UInt(p.XLEN.W))       // 当前指令PC
//  val imm = Input(UInt(p.XLEN.W))      // 符号扩展后的偏移量
//  val fn = Input(UInt(4.W))            // 分支操作码（见下方常量）
//
//  // 输出结果
//  val branchTaken = Output(Bool())     // 是否跳转
//  val targetPC = Output(UInt(p.XLEN.W)) // 跳转目标地址
//  val pcPlus4 = Output(UInt(p.XLEN.W)) // PC+4（用于JAL/JALR写回）
//  val cmp_out = Output(Bool())
//}
//
//class BR(implicit p: Parameters) extends Module with BRConsts {
//  val io = IO(new BRIO)
//
//  // 计算PC+4（用于JAL/JALR写回）
//  io.pcPlus4 := io.pc + 4.U
//
//  io.cmp_out := MuxLookup(io.fn, false.B)(
//    Seq(
//      BR_EQ  -> (io.rs1 === io.rs2),
//      BR_NE  -> (io.rs1 =/= io.rs2),
//      BR_LT  -> (io.rs1.asSInt < io.rs2.asSInt),
//      BR_GE  -> (io.rs1.asSInt >= io.rs2.asSInt),
//      BR_LTU -> (io.rs1 < io.rs2),
//      BR_GEU -> (io.rs1 >= io.rs2),
//      //JALR和JAL默认跳转？
//      BR_JALR -> true.B,
//      BR_JAL  -> true.B
//    )
//  )
//
//}
