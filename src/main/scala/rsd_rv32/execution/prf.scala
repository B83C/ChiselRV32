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


    val regBank = RegInit(Vec(p.PRF_DEPTH,0.U(32.W)))

        //定义地址写入和数据写入
        val writeAddr  = Vec(8,WireDefault(0.U(log2Ceil(p.PRF_DEPTH).W)))
        val writeData  = Vec(8,WireDefault(0.U(p.XLEN.W)))
        //地址写入
        writeAddr(0) := io.alu_wb_uop(0).bits.pdst
        writeAddr(1) := io.alu_wb_uop(1).bits.pdst
        writeAddr(2) := io.bu_wb_uop(0).bits.pdst
        writeAddr(3) := io.bu_wb_uop(1).bits.pdst
        writeAddr(4) := io.mul_wb_uop(0).bits.pdst
        writeAddr(5) := io.mul_wb_uop(1).bits.pdst
        writeAddr(6) := io.divrem_wb_uop(0).bits.pdst
        writeAddr(7) := io.divrem_wb_uop(1).bits.pdst

        //数据写入
        writeData(0) := io.alu_wb_uop(0).bits.pdst_value
        writeData(1) := io.alu_wb_uop(1).bits.pdst_value
        writeData(2) := io.bu_wb_uop(0).bits.pdst_value
        writeData(3) := io.bu_wb_uop(1).bits.pdst_value
        writeData(4) := io.mul_wb_uop(0).bits.pdst_value
        writeData(5) := io.mul_wb_uop(1).bits.pdst_value
        writeData(6) := io.divrem_wb_uop(0).bits.pdst_value
        writeData(7) := io.divrem_wb_uop(1).bits.pdst_value


        // 执行寄存器写入
  for (i <- 0 until 7) {
          regBank(writeAddr(i)) := writeData(i)
  }

        // EXU双发射读取端口
        for (i <- 0 until 2) {
          io.exu_issue_r_value1(i) := regBank(io.exu_issue_r_addr1(i))
          io.exu_issue_r_value2(i) := regBank(io.exu_issue_r_addr2(i))
        }

        // Store指令读取端口
        io.st_issue_r_value1 := regBank(io.st_issue_r_addr1)
        io.st_issue_r_value2 := regBank(io.st_issue_r_addr2)

        // Load指令读取端口
        io.ld_issue_r_value1 := regBank(io.ld_issue_r_addr1);


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