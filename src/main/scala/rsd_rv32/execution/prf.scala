package rsd_rv32.execution
import scala.collection.mutable.ArrayBuffer

import rsd_rv32.common._
import chisel3._
import chisel3.util._

class PRF_IO(implicit p: Parameters) extends CustomBundle{
  //FU写回的uop
  val alu_wb_uop = Input(Vec(p.ALU_NUM, Valid(new ALU_WB_uop())))
  val bu_wb_uop = Input(Vec(p.BU_NUM, Valid(new BU_WB_uop())))
  val mul_wb_uop = Input(Vec(p.MUL_NUM, Valid(new ALU_WB_uop())))
  val divrem_wb_uop = Input(Vec(p.DIV_NUM, Valid(new ALU_WB_uop())))

  //exu_issue的读地址与寄存器值
  val exu_issue_r_addr1 = Input(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W)))
  val exu_issue_r_addr2 = Input(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W)))
  val exu_issue_r_value1 = Output(Vec(2, UInt(p.XLEN.W)))
  val exu_issue_r_value2 = Output(Vec(2, UInt(p.XLEN.W)))
  //st_issue的读地址与寄存器值
  val st_issue_r_addr1 = Input(UInt(log2Ceil(p.PRF_DEPTH).W))
  val st_issue_r_addr2 = Input(UInt(log2Ceil(p.PRF_DEPTH).W))
  val st_issue_r_value1 = Output(UInt(p.XLEN.W))
  val st_issue_r_value2 = Output(UInt(p.XLEN.W))
  //ld_issue的读地址与寄存器值
  val ld_issue_r_addr1 = Input(UInt(log2Ceil(p.PRF_DEPTH).W))
  val ld_issue_r_value1 = Output(UInt(p.XLEN.W))

  //接收Rename Unit的AMT用于更新prf_valid
  val amt = Input(Vec(32,UInt(log2Ceil(p.PRF_DEPTH).W)))
  val rob_commitsignal = Vec(p.CORE_WIDTH, Valid(new ROBContent()))
  //prf_valid信号用于表示哪些寄存器已经就绪
  val prf_valid = Output(Vec(p.PRF_DEPTH, Bool()))
}

class PRF(implicit p: Parameters) extends CustomModule{
  val io = IO(new PRF_IO())
  val prf_value = Module(new PRF_Value())
  val prf_valid = Module(new PRF_Valid())
  (prf_value.io: Data).waiveAll :<>= (io: Data).waiveAll
  (prf_valid.io: Data).waiveAll :<>= (io: Data).waiveAll
}

class PRF_Value(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    //FU写回的uop
    val alu_wb_uop = Input(Vec(p.ALU_NUM, Valid(new ALU_WB_uop())))
    val bu_wb_uop = Input(Vec(p.BU_NUM, Valid(new BU_WB_uop())))
    val mul_wb_uop = Input(Vec(p.MUL_NUM, Valid(new ALU_WB_uop())))
    val divrem_wb_uop = Input(Vec(p.DIV_NUM, Valid(new ALU_WB_uop())))

    //exu_issue的读地址与寄存器值
    val exu_issue_r_addr1 = Input(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W)))
    val exu_issue_r_addr2 = Input(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W)))
    val exu_issue_r_value1 = Output(Vec(2, UInt(p.XLEN.W)))
    val exu_issue_r_value2 = Output(Vec(2, UInt(p.XLEN.W)))
    //st_issue的读地址与寄存器值
    val st_issue_r_addr1 = Input(UInt(log2Ceil(p.PRF_DEPTH).W))
    val st_issue_r_addr2 = Input(UInt(log2Ceil(p.PRF_DEPTH).W))
    val st_issue_r_value1 = Output(UInt(p.XLEN.W))
    val st_issue_r_value2 = Output(UInt(p.XLEN.W))
    //ld_issue的读地址与寄存器值
    val ld_issue_r_addr1 = Input(UInt(log2Ceil(p.PRF_DEPTH).W))
    val ld_issue_r_value1 = Output(UInt(p.XLEN.W))
  })

  //your code here
}

class PRF_Valid(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    //接收Rename Unit的AMT用于更新prf_valid
    val amt = Input(Vec(32,UInt(log2Ceil(p.PRF_DEPTH).W)))
    val rob_commitsignal = Vec(p.CORE_WIDTH, Valid(new ROBContent()))
    //prf_valid信号用于表示哪些寄存器已经就绪
    val prf_valid = Output(Vec(p.PRF_DEPTH, Bool()))
  })

  //your code here
}