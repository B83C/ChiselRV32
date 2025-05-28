package rsd_rv32.execution

import chisel3._
import chisel3.util._

import rsd_rv32.common._

import chisel3.experimental.hierarchy.{Definition, Instance, instantiable, public}

@instantiable 
class MmapDevice(addrBase: UInt, length_in_word: UInt) extends Module {
  @public val io = IO(new Bundle {
    val reset = Flipped(Bool())
    val addr  = Flipped(UInt(12.W))    // 12‑bit address as defined in RISCV manual
    val wdata = Flipped(Valid(UInt(32.W)))    // write data (ignored here)
    val wmask = Flipped(UInt(32.W))    // write data (ignored here)
    val ren   = Flipped(Bool())        // read enable
    val rdata = (UInt(32.W))   // read data
    val ready = (Bool())       // ready/valid response
  })

  // def read(offset: UInt): UInt
  // def write(offset: UInt, data: UInt): Unit

  val offset = io.addr - addrBase
  val hit = io.addr >= addrBase && offset < (length_in_word << 2) //hardcoded
}

@instantiable 
class McycleDevice(addrBase: UInt) extends MmapDevice(addrBase, 2.U) {
  val cycle = RegInit(0.U(64.W))

  when(!io.reset) {
    cycle := cycle + 1.U
  }.otherwise {
    cycle := 0.U
  }

  io.ready := hit 
  io.rdata := Mux(hit && io.ren,
    MuxLookup(offset, 0.U)(Seq(
      0.U -> cycle(31, 0),
      4.U -> cycle(63, 32)
    ))
    , 0.U)

  val wmask = io.wmask
  val wmask_n = ~wmask

  when(hit && io.wdata.valid) {
    switch (offset) {
      is (0.U) {
        printf(cf"Writing ${io.wdata.bits}%b & ${wmask}%b to lower bits of clock\n")
        cycle := Cat(cycle(63, 32), (io.wdata.bits & wmask) | (cycle(31, 0) & wmask_n))
      }
      is (4.U) {
        printf(cf"Writing ${io.wdata.bits}%b & ${wmask}%b to higher bits of clock\n")
        cycle := Cat((io.wdata.bits & wmask) | (cycle(63, 32) & wmask_n), cycle(31,0))
      }
    }
  }

  when(hit) {
    printf(cf"Reading clock ${cycle}%b\n")
  }

  // override def read(offset: UInt): UInt = {
  //   MuxLookup(offset, 0.U)(Seq(
  //     0.U -> cycle(31, 0),
  //     4.U -> cycle(63, 32)
  //   ))
  // }

  // override def write(offset: UInt, wdata: UInt): Unit = {
  // }
}

@instantiable 
class MtimeDevice(addrBase: UInt) extends McycleDevice(addrBase) 

class CSRFU_Default(implicit p: Parameters) extends CSRFU(Seq(
  Definition(new McycleDevice(p.CSR_MCYCLE_ADDR.U)),
  Definition(new MtimeDevice(p.CSR_MTIME_ADDR.U))
))

class CSRFU(devices: Seq[Definition[MmapDevice]])(implicit p: Parameters) extends FunctionalUnit with CSRConsts {
  override val properties = FUProps(
    Set(InstrType.CSR),
    bufferedInput = false,
    bufferedOutput = true
  )
  //TODO
  input.ready := true.B

  val uop = input.bits
  // (output.bits: Data).waiveAll :<>= (uop: Data).waiveAll

  // val mcycle = Module(new McycleDevice(p.CSR_MCYCLE_ADDR.U))
  // val mtime  = Module(new MtimeDevice(p.CSR_MTIME_ADDR.U))

  val all_devices = devices.map(m => Instance(m))

  val addr  = WireInit(0.U(p.XLEN.W))
  val wdata  = Wire(Valid(UInt(p.XLEN.W)))
  val wmask  = WireInit(0.U(p.XLEN.W))
  val ren  = Wire(Bool())

  all_devices.foreach { dev =>
    dev.io.addr  := addr
    dev.io.wdata := wdata
    dev.io.ren   := ren 
    dev.io.wmask   := wmask
    dev.io.reset := reset //TODO should be replaced by mispred
  }

  val rdata = VecInit(all_devices.map(_.io.rdata))
  val ready = VecInit(all_devices.map(_.io.ready))

  val first_ready_rdata = PriorityMux(ready.zip(rdata))

  val instr = Cat(uop.instr_, 0.U(7.W))
  val func3 = instr(14, 12)
  val csr = instr(31, 20)
  // val rd = instr(11, 7)
  val rs1 = instr(19, 15)

  wdata.valid := false.B
  wdata.bits := DontCare
  ren := false.B

  val should_input = rs1 =/= 0.U
  val should_output = uop.pdst =/= 0.U || !(opr_is(CSRRW) || opr_is(CSRRWI))

  val out = Wire(new WB_uop)
  (out: Data).waiveAll :<= (uop: Data).waiveAll
  // TODO: Not sure yet
  out.pdst_value.valid := should_output
  out.pdst_value.bits := first_ready_rdata

  output.bits := out
  output.valid := should_output

  ren := should_output

  //Works for both immediate and non-immediate versions

  def opr_is(check: UInt) : Bool = {
    func3 === check
  }

  val rs1_is_imm = (opr_is(CSRRWI) || opr_is(CSRRSI) || opr_is(CSRRSI))
  // val rs1_is_imm = uop.fu_signals.opr1_sel === OprSel.IMM // TODO: This is more appropriate
  val write_value = Mux(rs1_is_imm, rs1, uop.ps1_value)

  when(input.valid) {
    when(opr_is(CSRRW) || opr_is(CSRRWI)) {
      printf(cf"CSRRW instr detected csr ${csr}%x immediate ${rs1_is_imm} rs1 ${rs1} is_imm ${rs1_is_imm} write_value ${write_value}, first_ready_rdata ${first_ready_rdata}\n")
      //Util function for assembling and disassembling instruction
      // import Instr._
      // val decoded = disassemble(uop.instr, InstrType.CSR)
      // println(decoded)
      wdata.bits := write_value
      wdata.valid := true.B
      wmask := ~0.U(32.W)
      addr := csr
    }.elsewhen(opr_is(CSRRS) || opr_is(CSRRSI) || opr_is(CSRRC) || opr_is(CSRRCI)) {
      printf(cf"CSRRS(C) instr detected csr ${csr}%x valid ${should_input} mask ${uop.ps1_value}%b should_input ${should_input}\n")
      wdata.bits := Mux(opr_is(CSRRC), 0.U, ~0.U(32.W))
      wdata.valid := should_input
      wmask := write_value
      addr := csr
    }   
  }

  // Debugging
  out.debug(input.bits, should_output)
}
