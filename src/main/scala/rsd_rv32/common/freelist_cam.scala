package rsd_rv32.common

import chisel3._
import chisel3.util._

// 参数化的Freelist
// Freelist 本质上就是ringbuffer
// 为简化设计，一次只能读写multiShot个数据
// Head: Consumer
// Tail: Producer
class FreeListCam[T <: Data](
  depth: Int,  /* Depth of freelist */
  multiShotRead: Int = 1, /* To enable multiple read requests at once */ 
  multiShotWrite: Int = 1, /* To enable multiple read requests at once */ 
)(implicit p: Parameters) extends CustomModule {
  val io = IO(new Bundle {
    val checkpoint, restore = Flipped(Bool()) // Signals to checkpoint and restore both head and tail pointer
    val deq_request = Vec(multiShotRead, Decoupled(UInt(log2Ceil(depth).W)))
    val enq_request  = Flipped(Vec(multiShotWrite, Valid(UInt(log2Ceil(depth).W))))
    // val deq_request = Decoupled(Vec(multiShot, gen.cloneType))
    // val enq_request  = Flipped(Decoupled(Vec(multiShot, gen.cloneType)))
    val squeeze = Flipped(Bool())
    // val is_empty = (Bool())
  })

  // require(depth > 0 && isPow2(multiShot), "multishot length must be a power of two")
  val buffer = RegInit(0.U(depth.W))

  var tempMask = buffer
  var masks = for (i <- 0 until multiShotRead) yield {
    val enc = Mux(io.deq_request(i).ready, PriorityEncoderOH(~tempMask), 0.U)
    val valid = tempMask =/= ~0.U(depth.W) && io.deq_request(i).ready
    io.deq_request(i).valid := valid
    io.deq_request(i).bits := WireInit(0.U(log2Ceil(depth).W)) 
    // io.deq_request(i).bits := DontCare
    when(io.deq_request(i).fire) {
      val idx = OHToUInt(enc)
      printf(cf"fired enc; ${enc} idx ${idx} \n")
      io.deq_request(i).bits := idx
    }.otherwise {
      io.deq_request(i).bits := DontCare
      
    }
    tempMask = tempMask | enc
    enc
  }

  // deq_request := multiAlloc(buffer, multiShotRead)

  buffer := (buffer & ~io.enq_request.map(b => b.valid << b.bits).reduce(_ | _)) | masks.reduce(_ | _)

  when(io.squeeze) {
    buffer := 0.U(depth.W)
  }

  when (true.B) {
    printf(cf"buffer : ${buffer}%b \n")
  }
}
