package rsd_rv32.execution
import scala.collection.mutable.ArrayBuffer

import rsd_rv32.common._
import chisel3._
import chisel3.util._

class PRF_IO(implicit p: Parameters) extends CustomBundle{
  //FU写回的uop
  val alu_wb_uop = Flipped(Vec(p.ALU_NUM, Valid(new ALU_WB_uop())))
  val bu_wb_uop = Flipped(Vec(p.BU_NUM, Valid(new BU_WB_uop())))
  val mul_wb_uop = Flipped(Vec(p.MUL_NUM, Valid(new ALU_WB_uop())))
  val divrem_wb_uop = Flipped(Vec(p.DIV_NUM, Valid(new ALU_WB_uop())))
  val ldu_wb_uop = Flipped(Vec(p.LDU_NUM, Valid(new ALU_WB_uop())))
  val serialised_wb_uop = Flipped(Valid(new ALU_WB_uop()))

  //exu_issue的读地址与寄存器值
  val exu_issue_r_addr1 = Flipped(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W)))
  val exu_issue_r_addr2 = Flipped(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W)))
  val exu_issue_r_value1 = Vec(2, UInt(p.XLEN.W))
  val exu_issue_r_value2 = Vec(2, UInt(p.XLEN.W))
  //st_issue的读地址与寄存器值
  val st_issue_r_addr1 = Flipped(UInt(log2Ceil(p.PRF_DEPTH).W))
  val st_issue_r_addr2 = Flipped(UInt(log2Ceil(p.PRF_DEPTH).W))
  val st_issue_r_value1 = UInt(p.XLEN.W)
  val st_issue_r_value2 = UInt(p.XLEN.W)
  //ld_issue的读地址与寄存器值
  val ld_issue_r_addr1 = Flipped(UInt(log2Ceil(p.PRF_DEPTH).W))
  val ld_issue_r_value1 = UInt(p.XLEN.W)

  //接收Rename Unit的AMT用于更新prf_valid
  val amt = Flipped(Vec(32,UInt(log2Ceil(p.PRF_DEPTH).W)))
  val rob_commitsignal = Flipped(Vec(p.CORE_WIDTH, Valid(new ROBContent())))
  //prf_valid信号用于表示哪些寄存器已经就绪
  val prf_valid = Vec(p.PRF_DEPTH, Bool())
}

class PRF(implicit p: Parameters) extends CustomModule{
  val io = IO(new PRF_IO())
  // val prf_value = Module(new PRF_Value())
  // val prf_valid = Module(new PRF_Valid())
  // prf_value.io <> io
  // prf_valid.io <> io
// }

// class PRF_Value(implicit p: Parameters) extends Module {
  // val io = IO(new Bundle {
  //   //FU写回的uop
  //   val alu_wb_uop = Flipped(Vec(p.ALU_NUM, Valid(new ALU_WB_uop())))
  //   val bu_wb_uop = Flipped(Vec(p.BU_NUM, Valid(new BU_WB_uop())))
  //   val mul_wb_uop = Flipped(Vec(p.MUL_NUM, Valid(new ALU_WB_uop())))
  //   val divrem_wb_uop = Flipped(Vec(p.DIV_NUM, Valid(new ALU_WB_uop())))
  //   val ldu_wb_uop = Flipped(Vec(p.LDU_NUM, Valid(new ALU_WB_uop())))
  //   val serialised_wb_uop = Flipped(Vec(p.LDU_NUM, Valid(new ALU_WB_uop())))

  //   //exu_issue的读地址与寄存器值
  //   val exu_issue_r_addr1 = Flipped(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W)))
  //   val exu_issue_r_addr2 = Flipped(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W)))
  //   val exu_issue_r_value1 = (Vec(2, UInt(p.XLEN.W)))
  //   val exu_issue_r_value2 = (Vec(2, UInt(p.XLEN.W)))
  //   //st_issue的读地址与寄存器值
  //   val st_issue_r_addr1 = Flipped(UInt(log2Ceil(p.PRF_DEPTH).W))
  //   val st_issue_r_addr2 = Flipped(UInt(log2Ceil(p.PRF_DEPTH).W))
  //   val st_issue_r_value1 = (UInt(p.XLEN.W))
  //   val st_issue_r_value2 = (UInt(p.XLEN.W))
  //   //ld_issue的读地址与寄存器值
  //   val ld_issue_r_addr1 = Flipped(UInt(log2Ceil(p.PRF_DEPTH).W))
  //   val ld_issue_r_value1 = (UInt(p.XLEN.W))
  // })

  //your code here
  val regBank = RegInit(
      VecInit(Seq.fill(p.PRF_DEPTH) {
          0.U(32.W)
      })
  )
  //定义地址写入和数据写入
  val writeAddr  = Wire(Vec(7,UInt(log2Ceil(p.PRF_DEPTH).W)))
  val writeData  = Wire(Vec(7,UInt(p.XLEN.W)))
  //地址写入
  writeAddr(0) := io.alu_wb_uop(0).bits.pdst
  writeAddr(1) := io.alu_wb_uop(1).bits.pdst
  writeAddr(2) := io.bu_wb_uop(0).bits.pdst
  writeAddr(3) := io.mul_wb_uop(0).bits.pdst
  writeAddr(4) := io.divrem_wb_uop(0).bits.pdst
  writeAddr(5) := io.ldu_wb_uop(0).bits.pdst
  writeAddr(6) := io.serialised_wb_uop.bits.pdst

  //数据写入
  writeData(0) := io.alu_wb_uop(0).bits.pdst_value
  writeData(1) := io.alu_wb_uop(1).bits.pdst_value
  writeData(2) := io.bu_wb_uop(0).bits.pdst_value
  writeData(3) := io.mul_wb_uop(0).bits.pdst_value
  writeData(4) := io.divrem_wb_uop(0).bits.pdst_value
  writeData(5) := io.ldu_wb_uop(0).bits.pdst_value
  writeData(6) := io.serialised_wb_uop.bits.pdst_value

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
  io.ld_issue_r_value1 := regBank(io.ld_issue_r_addr1)
// }

// class PRF_Valid(implicit p: Parameters) extends Module {
//   val io = IO(new Bundle {
//     val alu_wb_uop = Flipped(Vec(p.ALU_NUM, Valid(new ALU_WB_uop())))
//     val bu_wb_uop = Flipped(Vec(p.BU_NUM, Valid(new BU_WB_uop())))
//     val mul_wb_uop = Flipped(Vec(p.MUL_NUM, Valid(new ALU_WB_uop())))
//     val divrem_wb_uop = Flipped(Vec(p.DIV_NUM, Valid(new ALU_WB_uop())))
//     val ldu_wb_uop = Flipped(Vec(p.LDU_NUM, Valid(new ALU_WB_uop())))
//     val serialised_wb_uop = Flipped(Valid(new ALU_WB_uop()))
//     //接收Rename Unit的AMT用于更新prf_valid
//     val amt = Flipped(Vec(32,UInt(log2Ceil(p.PRF_DEPTH).W)))
//     val rob_commitsignal = Flipped(Vec(p.CORE_WIDTH, Valid(new ROBContent())))
//     //prf_valid信号用于表示哪些寄存器已经就绪
//     val prf_valid = (Vec(p.PRF_DEPTH, Bool()))
//   })

  //your code here
  val prf_valid = RegInit(VecInit((0 until p.PRF_DEPTH).map(i => if(i == 0) true.B else false.B)))
  io.prf_valid := prf_valid

  val flush = Wire(Bool())
  flush := io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred


  //0置1的逻辑
  when(!flush){
    for(i <- 0 until p.ALU_NUM){
      when(io.alu_wb_uop(i).valid){
        prf_valid(io.alu_wb_uop(i).bits.pdst) := true.B
      }
    }
    for(i <- 0 until p.BU_NUM){
      when(io.bu_wb_uop(i).valid && !io.bu_wb_uop(i).bits.is_conditional){
        prf_valid(io.bu_wb_uop(i).bits.pdst) := true.B
      }
    }
    for(i <- 0 until p.MUL_NUM){
      when(io.mul_wb_uop(i).valid){
        prf_valid(io.mul_wb_uop(i).bits.pdst) := true.B
      }
    }
    for(i <- 0 until p.DIV_NUM){
      when(io.divrem_wb_uop(i).valid){
        prf_valid(io.divrem_wb_uop(i).bits.pdst) := true.B
      }
    }
    for(i <- 0 until p.LDU_NUM){
      when(io.ldu_wb_uop(i).valid){
        prf_valid(io.ldu_wb_uop(i).bits.pdst) := true.B
      }
    }
    when(io.serialised_wb_uop.valid){
      prf_valid(io.serialised_wb_uop.bits.pdst) := true.B
    }
  }

  //1置0的逻辑
  def hasPd(rob_type : ROBType.Type, rd : UInt) : Bool = {
    (rob_type === ROBType.Arithmetic || rob_type === ROBType.Jump || rob_type === ROBType.CSR) && (rd =/= 0.U)
  }

  val rob_valid_bits = Wire(UInt(2.W))
  rob_valid_bits := io.rob_commitsignal(0).valid ## io.rob_commitsignal(1).valid
  
  when(!flush){
    switch(rob_valid_bits){
      is("b11".U){
        when(hasPd(io.rob_commitsignal(0).bits.rob_type, io.rob_commitsignal(0).bits.payload(4,0))){
          when(hasPd(io.rob_commitsignal(1).bits.rob_type, io.rob_commitsignal(1).bits.payload(4,0))){
            when(io.rob_commitsignal(0).bits.payload(4,0) === io.rob_commitsignal(1).bits.payload(4,0)){
              prf_valid(io.amt(io.rob_commitsignal(0).bits.payload(4,0))) := false.B
              prf_valid(io.rob_commitsignal(0).bits.payload(5+log2Ceil(p.PRF_DEPTH)-1, 5)) := false.B
            }.otherwise{
              prf_valid(io.amt(io.rob_commitsignal(0).bits.payload(4,0))) := false.B
              prf_valid(io.amt(io.rob_commitsignal(1).bits.payload(4,0))) := false.B
            }
          }.otherwise{
            prf_valid(io.amt(io.rob_commitsignal(0).bits.payload(4,0))) := false.B
          }
        }.otherwise{
          when(hasPd(io.rob_commitsignal(1).bits.rob_type, io.rob_commitsignal(1).bits.payload(4,0))){
            prf_valid(io.amt(io.rob_commitsignal(1).bits.payload(4,0))) := false.B
          }
        }
      }
      is("b10".U){
        when(hasPd(io.rob_commitsignal(0).bits.rob_type, io.rob_commitsignal(0).bits.payload(4,0))){
          prf_valid(io.amt(io.rob_commitsignal(0).bits.payload(4,0))) := false.B
        }
      }
    }
  }

  //flush的逻辑
  def in_amt(prf_index: UInt, amt: Vec[UInt]): Bool = {
    val temp = WireDefault(VecInit(Seq.fill(32)(false.B)))
    for(i <- 0 until 32){
      temp(i) := amt(i) === prf_index
    }
    temp.reduce(_||_)
  }

  val new_amt = WireDefault(io.amt)
  when(flush){
    when(hasPd(io.rob_commitsignal(0).bits.rob_type, io.rob_commitsignal(0).bits.payload(4,0))){
      new_amt(io.rob_commitsignal(0).bits.payload(4,0)) := io.rob_commitsignal(0).bits.payload(5+log2Ceil(p.PRF_DEPTH)-1, 5)
    }
    for(i <- 0 until p.PRF_DEPTH){
      prf_valid(i) := Mux(in_amt(i.U, new_amt), prf_valid(i), false.B)
    }
  }
}
