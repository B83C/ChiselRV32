package rsd_rv32.common

import chisel3._
import chisel3.util._

// 参数化的Freelist
// Freelist 本质上就是ringbuffer
// 为简化设计，一次只能读写multiShot个数据
// Head: Consumer
// Tail: Producer
class FreeList[T <: Data](
  dType: T,    /* Freelist data type */
  depth: Int,  /* Depth of freelist */
  multiShot: Int = 1, /* To enable multiple read requests at once */ 
)(implicit p: Parameters) extends CustomModule {
  val depth_bits = log2Ceil(depth)
  val io = IO(new Bundle {
    val checkpoint, restore = Input(Bool()) // Signals to checkpoint and restore both head and tail pointer
    val deq_request = Decoupled(Vec(multiShot, dType))
    val enq_request  = Flipped(Decoupled(Vec(multiShot, dType)))
    // val is_empty = Output(Bool())
  })

  require(depth > 0 && isPow2(multiShot), "multishot length must be a power of two")

  // val buffer = Vec(depth >> log2Ceil(multiShot), Vec(multiShot, dType))
  val buffer = Mem(depth >> log2Ceil(multiShot), Vec(multiShot, dType))
  // To efficiently encode full and empty states
  val
    head, tail,
    head_checkpoint, tail_checkpoint
    = RegInit(0.U((depth_bits + 1).W))
  val whead = head(depth_bits - 1, 0)
  val wtail = tail(depth_bits - 1, 0)
  val hwrap = head(depth_bits)
  val twrap = tail(depth_bits)

  val empty = head === tail
  val full = head === tail && hwrap =/= twrap

  // io.is_empty := empty
  io.deq_request.valid := !empty
  io.enq_request.ready := !full

  //Asynchronous read
  io.deq_request.bits := buffer(head)
  when(io.deq_request.fire) {
    printf(cf"dequeuing ${io.deq_request.bits}")
    head := head + 1.U
  }

  //Synchronous write
  when(io.enq_request.fire) {
    printf(cf"Enqueuing ${io.enq_request.bits}")
    buffer(tail) := io.enq_request.bits
    tail := tail + 1.U
  }

  //Checkpointing
  when(io.checkpoint && !io.restore) {
    head_checkpoint := head
    tail_checkpoint := tail
  }
  when(io.restore) {
     head := head_checkpoint
     tail := tail_checkpoint
  }

  when (true.B) {
    printf(cf"head : 0x$head%x, tail : 0x$tail%x, empty: $empty\n")
  }
}
