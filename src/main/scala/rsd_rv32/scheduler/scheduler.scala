package rsd_rv32.scheduler

import chisel3._
import chisel3.util._


class DestinationRAM(params: DispatchUnitParams) extends Module {
  val io = IO(new Bundle {
    val wrEn = Input(Bool())
    val wrReg = Input(UInt(params.phyRegBits.W))
    val wrIQIdx = Input(UInt(params.iqIdxBits.W))
    val rdReg1 = Input(UInt(params.phyRegBits.W))
    val rdIQIdx1 = Output(UInt(params.iqIdxBits.W))
    // ...类似rdReg2...
  })
  val ram = SyncReadMem(params.phyRegNum, UInt(params.iqIdxBits.W))
}


class ReadyBitTable(params: DispatchUnitParams) extends Module {
  val io = IO(new Bundle {
    val init = Flipped(Valid(new Bundle { /*...*/ }))
    val setReady = Flipped(Valid(new Bundle { /*...*/ }))
    val readyBits = Output(Vec(params.iqEntries, Vec(2, Bool())))
  })
  val readyBits = RegInit(VecInit(Seq.fill(params.iqEntries)(VecInit(false.B, false.B))))
}


class ReadyBitTable(params: DispatchUnitParams) extends Module {
  val io = IO(new Bundle {
    val init = Flipped(Valid(new Bundle { /*...*/ }))
    val setReady = Flipped(Valid(new Bundle { /*...*/ }))
    val readyBits = Output(Vec(params.iqEntries, Vec(2, Bool())))
  })
  val readyBits = RegInit(VecInit(Seq.fill(params.iqEntries)(VecInit(false.B, false.B))))
}


class WAT(params: DispatchUnitParams) extends Module {
  val io = IO(new Bundle {
    val addEntry = Flipped(Valid(new Bundle { /*...*/ }))
    val wakeup = Flipped(Valid(UInt(params.phyRegBits.W)))
    val setReady = Valid(new Bundle { /*...*/ })
  })

  val entries = Reg(Vec(params.watEntries, new Bundle { /*...*/ }))
}
