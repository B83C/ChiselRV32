package rsd_rv32.execution
import scala.collection.mutable.ArrayBuffer

import rsd_rv32.common._
import rsd_rv32.scheduler._
import chisel3._
import chisel3.util._
import rsd_rv32.common.Utils.ReadValueRequest

class PRF_IO(read_port_count: Int)(implicit p: Parameters) extends CustomBundle{
  //FU写回的uop TODO
  val wb_uop = Flipped(Vec(p.FU_NUM, Valid(new WB_uop)))

  val read_requests = Flipped(Vec(read_port_count, ReadValueRequest(UInt(p.XLEN.W), UInt(log2Ceil(p.PRF_DEPTH).W))))

  //接收Rename Unit的AMT用于更新prf_valid
  val amt = Flipped(Vec(32, UInt(log2Ceil(p.PRF_DEPTH).W)))
  val rob_commitsignal = Flipped(ROB.CommitSignal)  //ROB提交时的广播信号，rob正常提交指令时更新amt与rmt，发生误预测时对本模块进行恢复
  val rob_controlsignal = Flipped(ROB.ControlSignal) //来自于ROB的控制信号
  //prf_valid信号用于表示哪些寄存器已经就绪
  val prf_valid = Vec(p.PRF_DEPTH, Bool())
}

class PRF(read_port_count: Int)(implicit p: Parameters) extends CustomModule{
  val io = IO(new PRF_IO(read_port_count))

  val regBank = RegInit(
      VecInit(Seq.fill(p.PRF_DEPTH) {
          0.U(32.W)
      })
  )
  val prf_valid = RegInit(VecInit((~0.U(p.PRF_DEPTH.W)).asBools))

  io.prf_valid := prf_valid

  // 执行寄存器写入
  (io.wb_uop.map(x => (x.bits, x.valid && x.bits.pdst_value.valid))).foreach{ case (x, y) => 
    when(y) {
      regBank(x.pdst) := x.pdst_value.bits
      prf_valid(x.pdst) := true.B
    }
  }
  io.read_requests.foreach(request => {
    request.value := Mux(request.valid, regBank(request.addr), 0.U)
  })


  val flush = io.rob_controlsignal.valid && io.rob_controlsignal.bits.isMispredicted

  //1置0的逻辑
  def hasPd(rob_type : ROBType.Type, rd : UInt) : Bool = {
    (rob_type === ROBType.Arithmetic || rob_type === ROBType.Jump || rob_type === ROBType.CSR) && (rd =/= 0.U)
  }

  val rob_valid_bits = Wire(UInt(2.W))
  rob_valid_bits := io.rob_commitsignal.bits(0).valid ## io.rob_commitsignal.bits(1).valid
  
  when(!flush && io.rob_commitsignal.valid){
    switch(rob_valid_bits){
      is("b11".U){
        when(hasPd(io.rob_commitsignal.bits(0).rob_type, io.rob_commitsignal.bits(0).payload(4,0))){
          when(hasPd(io.rob_commitsignal.bits(1).rob_type, io.rob_commitsignal.bits(1).payload(4,0))){
            when(io.rob_commitsignal.bits(0).payload(4,0) === io.rob_commitsignal.bits(1).payload(4,0)){
              prf_valid(io.amt(io.rob_commitsignal.bits(0).payload(4,0))) := false.B
              prf_valid(io.rob_commitsignal.bits(0).payload(5+log2Ceil(p.PRF_DEPTH)-1, 5)) := false.B
            }.otherwise{
              prf_valid(io.amt(io.rob_commitsignal.bits(0).payload(4,0))) := false.B
              prf_valid(io.amt(io.rob_commitsignal.bits(1).payload(4,0))) := false.B
            }
          }.otherwise{
            prf_valid(io.amt(io.rob_commitsignal.bits(0).payload(4,0))) := false.B
          }
        }.otherwise{
          when(hasPd(io.rob_commitsignal.bits(1).rob_type, io.rob_commitsignal.bits(1).payload(4,0))){
            prf_valid(io.amt(io.rob_commitsignal.bits(1).payload(4,0))) := false.B
          }
        }
      }
      is("b10".U){
        when(hasPd(io.rob_commitsignal.bits(0).rob_type, io.rob_commitsignal.bits(0).payload(4,0))){
          prf_valid(io.amt(io.rob_commitsignal.bits(0).payload(4,0))) := false.B
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
    when(hasPd(io.rob_commitsignal.bits(0).rob_type, io.rob_commitsignal.bits(0).payload(4,0))){
      new_amt(io.rob_commitsignal.bits(0).payload(4,0)) := io.rob_commitsignal.bits(0).payload(5+log2Ceil(p.PRF_DEPTH)-1, 5)
    }
    for(i <- 0 until p.PRF_DEPTH){
      prf_valid(i) := Mux(in_amt(i.U, new_amt), prf_valid(i), false.B)
    }
  }
}
