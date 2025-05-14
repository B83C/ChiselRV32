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
    val read_request = Decoupled(Vec(multiShot, dType))
    val dealloc_request  = Flipped(Decoupled(Vec(multiShot, dType)))
  })

  require(depth > 0 && isPow2(multishot), "multishot length must be a power of two")

  val buffer = Vec(depth >> log2Ceil(multishot), Vec(multiShot, dType))
  // To efficiently encode full and empty states
  val head, tail = RegInit(0.U((depth_bits + 1).W))
  val whead = head.bits(depth_bits - 1, 0)
  val wtail = tail.bits(depth_bits - 1, 0)
  val hwrap = head.bits(depth_bits)
  val twrap = tail.bits(depth_bits)
  val head_checkpoint, tail_checkpoint
    = RegInit(0.U((depth_bits + 1).W))

  val empty = head === tail
  val full = head === tail && hwrap =/= twrap

  read_request.valid := !empty
  dealloc_request.ready := !full

  when(io.read_request.fire()) {
    read_request.bits := buffer(head).bits
    head := head + 1
  }

  when(io.dealloc_request.fire()) {
    buffer(head).bits := read_request.bits
    tail := tail + 1
  }

  when(io.checkpoint && !io.restore) {
    head_checkpoint := head
    tail_checkpoint := tail
  }
  when(io.restore) {
     head := head_checkpoint
     tail := tail_checkpoint
  }

}
