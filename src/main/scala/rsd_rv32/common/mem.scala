package rsd_rv32.common

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile
import firrtl.annotations.MemoryLoadFileType
import scala.io.Source
import java.nio.file.Files

class if_mem extends Bundle {
  val instAddr = Valid(UInt(64.W))
}

class mem_id(val instWidth: Int) extends Bundle {
  val inst = (Vec(instWidth, UInt(32.W)))
}

class lsu_mem_c extends Bundle {
  val atomFlag  = (Bool())
  val data_addr  = (UInt(64.W))
  val write_en   = (Bool())
  val data_into_mem = (UInt(64.W))
  val func3     = (UInt(3.W))
}

class mem_lsu_c extends Bundle {
  val data_out_mem = (UInt(64.W))
}

class mem(mem_path: String, memDepth: Int, instWidth: Int) extends CustomModule {
  val io = IO(new Bundle {
    val reset   = Flipped(Bool())           // Mem reset signal
    val if_mem  = Flipped(new if_mem()) 
    val ex_mem  = Flipped( 
      new lsu_mem_c
    )
    val mem_id  = new mem_id(instWidth)
    val mem_lsu = new mem_lsu_c
  })


  val memInside = SyncReadMem(memDepth, Vec(4, UInt(8.W))).suggestName("mem")

  //  var memAddrDiff = Wire(UInt(2.W))
  //  for (i <- 0 to 3) {
  //    val memAddrDiff = i - io.ex_mem.data_addr(1,0).litValue().toInt
  //    if (i >= io.ex_mem.data_addr(1,0).litValue().toInt) {
  //      memWriteVec(i) := io.ex_mem.data_into_mem(memAddrDiff * 8 + 8, memAddrDiff * 8)
  //    } else {
  //      memWriteVec(i) := io.ex_mem.data_into_mem((32 - memAddrDiff * 8) + 8, 32 - memAddrDiff * 8)
  //    }
  //  }

  val memWriteVec  = Wire(Vec(4, UInt(8.W)))
  val memWriteVec1 = Wire(Vec(4, UInt(8.W)))
  for (i <- 0 to 3) {
    memWriteVec(i)  := io.ex_mem.data_into_mem(i * 8 + 7, i * 8)
    memWriteVec1(i) := io.ex_mem.data_into_mem((i + 4) * 8 + 7, (i + 4) * 8)
  }
  (0 until 8).map(_.asUInt)
  switch(io.ex_mem.data_addr(1, 0)) {
    is(1.U) {
      for (i <- 0 to 2) {
        memWriteVec(i + 1)  := io.ex_mem.data_into_mem(i * 8 + 7, i * 8)
        memWriteVec1(i + 1) := io.ex_mem.data_into_mem((i + 4) * 8 + 7, (i + 4) * 8)
      }
      for (i <- 0 to 0) {
        memWriteVec(i)  := io.ex_mem.data_into_mem((i + 3) * 8 + 7, (i + 3) * 8)
        memWriteVec1(i) := io.ex_mem.data_into_mem((i + 7) * 8 + 7, (i + 7) * 8)
      }
    }
    is(2.U) {
      for (i <- 0 to 1) {
        memWriteVec(i + 2)  := io.ex_mem.data_into_mem(i * 8 + 7, i * 8)
        memWriteVec1(i + 2) := io.ex_mem.data_into_mem((i + 4) * 8 + 7, (i + 4) * 8)
      }
      for (i <- 0 to 1) {
        memWriteVec(i)  := io.ex_mem.data_into_mem((i + 2) * 8 + 7, (i + 2) * 8)
        memWriteVec1(i) := io.ex_mem.data_into_mem((i + 6) * 8 + 7, (i + 6) * 8)
      }
    }
    is(3.U) {
      for (i <- 0 to 0) {
        memWriteVec(i + 3)  := io.ex_mem.data_into_mem(i * 8 + 7, i * 8)
        memWriteVec1(i + 3) := io.ex_mem.data_into_mem((i + 4) * 8 + 7, (i + 4) * 8)
      }
      for (i <- 0 to 2) {
        memWriteVec(i)  := io.ex_mem.data_into_mem((i + 1) * 8 + 7, (i + 1) * 8)
        memWriteVec1(i) := io.ex_mem.data_into_mem((i + 5) * 8 + 7, (i + 5) * 8)
      }
    }
  }

  // loadMemoryFromFile(memInside, mem_path, MemoryLoadFileType.Hex)
  // loadMemoryFromFile(memInside, mem_path, MemoryLoadFileType.Hex)

  val data_addr   = Wire(UInt(64.W))
  val dataAddrP1 = Wire(UInt(64.W))
  val dataAddrP2 = Wire(UInt(64.W))
  data_addr   := io.ex_mem.data_addr >> 2.U
  dataAddrP1 := data_addr + 1.U
  dataAddrP2 := data_addr + 2.U

  for (i <- 0 until instWidth) {
    val last_instr = RegInit(0x13.U(32.W))
    val valid = RegNext(io.if_mem.instAddr.valid)
    val read_mem = memInside.read(io.if_mem.instAddr.bits + i.U).reduce((acc, elem) => Cat(elem, acc))
    io.mem_id.inst(i) := Mux(
      io.reset || !valid,
      last_instr,
      read_mem
    )
    when(valid)  {
      last_instr := read_mem
    }
  }
  io.mem_lsu.data_out_mem := Cat(
    memInside.read(dataAddrP1).reduce((acc, elem) => Cat(elem, acc)),
    memInside.read(data_addr).reduce((acc, elem) => Cat(elem, acc))
  )

  val memChoose0 = Wire(Vec(4, Bool()))
  val memChoose1 = Wire(Vec(4, Bool()))
  for (i <- 0 to 3) {
    memChoose0(i) := false.B
    memChoose1(i) := false.B
  }
  switch(io.ex_mem.func3) {
    is(0.U) { // SB
      memChoose0(io.ex_mem.data_addr(1, 0)) := true.B
    }
    is(1.U) { // SH
      when(io.ex_mem.data_addr(1, 0) =/= 3.U) {
        memChoose0(io.ex_mem.data_addr(1, 0))       := true.B
        memChoose0(io.ex_mem.data_addr(1, 0) + 1.U) := true.B
      }.otherwise {
        memChoose0(3) := true.B
        memChoose1(0) := true.B
      }
    }
    is(2.U) { // SW
      memChoose0(0) := Mux(io.ex_mem.data_addr(1, 0) === 0.U, true.B, false.B)
      memChoose0(1) := Mux(io.ex_mem.data_addr(1, 0) <= 1.U, true.B, false.B)
      memChoose0(2) := Mux(io.ex_mem.data_addr(1, 0) <= 2.U, true.B, false.B)
      memChoose0(3) := Mux(io.ex_mem.data_addr(1, 0) <= 3.U, true.B, false.B)
      memChoose1(0) := Mux(io.ex_mem.data_addr(1, 0) >= 1.U, true.B, false.B)
      memChoose1(1) := Mux(io.ex_mem.data_addr(1, 0) >= 2.U, true.B, false.B)
      memChoose1(2) := Mux(io.ex_mem.data_addr(1, 0) >= 3.U, true.B, false.B)
    }
    is(3.U) { // SD
      memChoose0(0) := Mux(io.ex_mem.data_addr(1, 0) === 0.U, true.B, false.B)
      memChoose0(1) := Mux(io.ex_mem.data_addr(1, 0) <= 1.U, true.B, false.B)
      memChoose0(2) := Mux(io.ex_mem.data_addr(1, 0) <= 2.U, true.B, false.B)
      memChoose0(3) := Mux(io.ex_mem.data_addr(1, 0) <= 3.U, true.B, false.B)
      memChoose1(0) := Mux(io.ex_mem.data_addr(1, 0) >= 1.U, true.B, false.B)
      memChoose1(1) := Mux(io.ex_mem.data_addr(1, 0) >= 2.U, true.B, false.B)
      memChoose1(2) := Mux(io.ex_mem.data_addr(1, 0) >= 3.U, true.B, false.B)
    }
  }

  when(io.ex_mem.write_en) {
    when(io.ex_mem.func3 === 3.U) {
      memInside.write(data_addr, memWriteVec, memChoose0)
      memInside.write(dataAddrP1, memWriteVec, memChoose1)
      memInside.write(dataAddrP1, memWriteVec1, memChoose0)
      memInside.write(dataAddrP2, memWriteVec1, memChoose1)
    }.otherwise {
      memInside.write(data_addr, memWriteVec, memChoose0)
      memInside.write(dataAddrP1, memWriteVec, memChoose1)
    }
  }

}
