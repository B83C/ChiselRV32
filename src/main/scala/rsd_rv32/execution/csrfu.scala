package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

import rsd_rv32.frontend._

abstract class MmapDevice(addrBase: UInt, length_in_word: UInt) extends Module {
  val io = IO(new Bundle {
    val addr  = Input(UInt(12.W))    // 12‑bit address as defined in RISCV manual
    val wdata = Input(Valid(UInt(32.W)))    // write data (ignored here)
    val ren   = Input(Bool())        // read enable
    val rdata = Output(UInt(32.W))   // read data
    val ready = Output(Bool())       // ready/valid response
  })

  def read(offset: UInt): UInt
  def write(offset: UInt, data: UInt): UInt

  val offset = (io.addr - addrBase).asSInt
  val hit = offset > 0 && offset < (length_in_word << 2) //hardcoded
  io.ready := hit 
  io.rdata := Mux(hit && io.ren, read(offset), 0.U)
  when(hit && io.wdata.valid)  {
    write(offset, io.wdata.bits)
  }
}

class McycleDevice(addrBase: UInt) extends MmapDevice(addrBase, 2) {
  val cycle = RegInit(0.U(64.W))
  cycle := cycle + 1.U

  override def read(offset: UInt): UInt = {
    MuxLookup(offset, 0.U, Seq(
      0.U -> cycle(31, 0),
      4.U -> cycle(63, 32)
    ))
  }

  override def write(offset: UInt, wdata: UInt): UInt = {
    switch (offset) {
      is (0.U) {
        cycle(31, 0) := wdata
      }
      is (4.U) {
        cycle(63, 32) := wdata
      }
    }
  }
}

class MtimeDevice(addrBase: UInt) extends Mcycle(addrBase, 2) 


class CSRFU(implicit p: Parameters) extends FunctionalUnit with CSRConsts {
  override def supportedInstrTypes = Set(InstrType.CSR)

  val out = Valid(new ALU_WB_uop())
  val uop = io.uop

  val mcycle = Module(new McycleDevice(0xEEF))
  val mtime  = Module(new MtimeDevice(0xEAF))

  val all_devices = Seq(mcycle, mtime)

  val addr  = Wire(UInt(p.XLEN.W))
  val wdata  = Wire(Valid(UInt(p.XLEN.W)))
  val ren  = Wire(Bool())

  all_devices.foreach { dev =>
    dev.io.addr  := addr
    dev.io.wdata := wdata
    dev.io.ren   := ren 
  }

  val rdata = VecInit(all_devices.map(_.rdata))
  val ready = VecInit(all_devices.map(_.ready))

  val first_ready_data = PriorityMux(ready.zip(rdata))

  val instr = Cat(uop.instr, 0.U(7.W))
  val func3 = instr(14, 12)
  val csr = instr(31, 20)

  wdata.valid := false.B
  ren := false.B

  io.out.bits.rob_index := uop.rob_index
  io.out.valid := false.B

  switch(func3) {
    is(CSRRW) {
      ren := true.B
      wdata.bits := uop.ps1_value
      wdata.valid := true.B
      addr := csr
      io.out.bits.pdst := uop.pdst
      io.out.bits.pdst_value := first_ready_rdata
      io.out.vaild := true.B
    }
  }
}
