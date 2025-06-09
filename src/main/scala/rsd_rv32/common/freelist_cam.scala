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
  maskedRegions: Seq[Range] = Seq(),
  preOccupiedRegion: Seq[Range] = Seq(),
  needCheckpoint: Boolean = false,
  sharedState: Boolean = false,
)(implicit p: Parameters) extends CustomModule {
  val io = IO(new Bundle {
    val deq_request = Vec(multiShotRead, Decoupled(UInt(log2Ceil(depth).W)))
    val enq_request  = Flipped(Vec(multiShotWrite, Valid(UInt(log2Ceil(depth).W))))
    val squeeze = Flipped(Bool())
    val restore = if (needCheckpoint) Some(Flipped(Bool())) else None// Signals to checkpoint and restore both head and tail pointer
    val restore_mapping = if (needCheckpoint) Some(Flipped(Vec(depth, Bool()))) else None// Signals to checkpoint and restore both head and tail pointer
    val state = if (sharedState) Some(Vec(depth, Bool())) else None// Signals to checkpoint and restore both head and tail pointer
  })

  // 由一组ranges，生成对应的bitmask
  val preOccupied = preOccupiedRegion.flatten.foldLeft(0.U(depth.W)){ case (mask, bit) => mask.bitSet(bit.U, true.B)}
  val masked = maskedRegions.flatten.foldLeft(0.U(depth.W)){ case (mask, bit) => mask.bitSet(bit.U, true.B)}
  // require(depth > 0 && isPow2(multiShot), "multishot length must be a power of two")
  // val buffer = RegInit(0.U(depth.W) | preOccupied)
  val buffer = RegInit(VecInit(preOccupied.asBools))

  val should_halt_requests = if(needCheckpoint) io.restore.get else false.B

  var tempMask = buffer.asUInt | masked
  io.deq_request.map{case deq_request => {
      val enc = Mux(deq_request.ready, PriorityEncoderOH(~tempMask(depth - 1, 0)), 0.U)
      val valid = tempMask =/= ~0.U(depth.W) && deq_request.ready
      deq_request.valid := valid
      deq_request.bits := WireInit(0.U(log2Ceil(depth).W)) 
      // deq_request.bits := DontCare
      val idx = OHToUInt(enc)
      when(deq_request.fire) {
        dbg(cf"fired enc; ${enc} idx ${idx} \n")
        deq_request.bits := idx
        when(!should_halt_requests) {
          buffer(idx) := true.B
        }
      }.otherwise {
        deq_request.bits := DontCare
      
      }
      tempMask = tempMask | enc
  }}

  // deq_request := multiAlloc(buffer, multiShotRead)

  // buffer := (buffer & ~io.enq_request.map(b => b.valid << b.bits).reduce(_ | _)) | masks.reduce(_ | _)

  io.enq_request.foreach{case (enq) => {
    when(!should_halt_requests) {
      when(enq.valid) {
        buffer(enq.bits) := false.B
      }
    }
  }}

  when(io.squeeze) {
    buffer := VecInit(0.U(depth.W).asBools)
  }

  if(needCheckpoint) {
    val restore = io.restore.get
    val restore_mapping = io.restore_mapping.get
    when(restore) {
      buffer := restore_mapping
      // printf(cf"restore_mapping : ${buffer}%b \n")
    }
  }

  if(sharedState) {
     io.state.get := buffer
  }

  when (true.B) {
    val buf = buffer.asUInt
    dbg(cf"buffer : ${buf}%b\n")
  }
}
