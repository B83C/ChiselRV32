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
  // val amt = Flipped(Vec(32, UInt(log2Ceil(p.PRF_DEPTH).W)))
  val rob_commitsignal = Flipped(ROB.CommitSignal)  //ROB提交时的广播信号，rob正常提交指令时更新amt与rmt，发生误预测时对本模块进行恢复
  val rob_controlsignal = Flipped(new ROBControlSignal) //来自于ROB的控制信号
  // prf_valid信号用于表示哪些寄存器已经就绪
  // val prf_valid = Vec(p.PRF_DEPTH, Bool())
}

class PRF(read_port_count: Int)(implicit p: Parameters) extends CustomModule{
  val io = IO(new PRF_IO(read_port_count))

  val regBank = RegInit(
      VecInit(Seq.fill(p.PRF_DEPTH) {
          0.U(32.W)
      })
  )

  io.wb_uop.foreach(x => {
    when(x.valid && x.bits.pdst.valid) {
      regBank(x.bits.pdst.bits) := x.bits.pdst_value
    }
  })

  io.read_requests.foreach(request => {
    request.value := Mux(request.addr =/= 0.U, regBank(request.addr), 0.U)
  })
}
