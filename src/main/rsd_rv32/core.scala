package rsd_rv32

import chisel3._
import chisel3.util._

import rsd_rv32.common._
import rsd_rv32.scheduler._
import rsd_rv32.frontend._
import rsd_rv32.execution._

// 主要核心
class Core()(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val imem = new InstructionMemoryInterface
    val dmem = new DataMemoryInterface
  })

  // Pipeline stages
  val fetch = Module(new FetchUnit)
  val decode = Module(new DecodeUnit)
  val rename = Module(new RenameUnit)
  val dispatch = Module(new DispatchUnit)
  val issue = Module(new IssueUnit)
  val execute = Module(new ExecuteUnit)
  val writeback = Module(new WritebackUnit)
  val commit = Module(new CommitUnit)

  // Major structures
  val rob = Module(new ReorderBuffer(64))  // 64-entry ROB
  val physRegFile = Module(new PhysicalRegFile(128)) // 128 physical registers
  val freeList = Module(new FreeList(128, 32))  // 32 architectural registers

  // Connect pipeline stages
  fetch.io.imem <> io.imem
  decode.io.in <> fetch.io.out
  rename.io.in <> decode.io.out
  dispatch.io.in <> rename.io.out
  issue.io.in <> dispatch.io.out
  execute.io.in <> issue.io.out
  writeback.io.in <> execute.io.out
  commit.io.in <> writeback.io.out

  // Connect structural components
  rename.io.freeList <> freeList.io
  dispatch.io.rob <> rob.io.alloc
  writeback.io.rob <> rob.io.writeback
  commit.io.rob <> rob.io.commit
}
