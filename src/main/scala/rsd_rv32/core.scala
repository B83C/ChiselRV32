package rsd_rv32

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

import rsd_rv32.common._
import rsd_rv32.scheduler._
import rsd_rv32.frontend._
import rsd_rv32.execution._

// Top Level Structure
class Core(implicit p: Parameters) extends CustomModule {
  val io = IO(new Bundle {
      val rst = Bool()
  })

  val mem_depth = 1 << 10
  val mem = Module(new mem(mem_depth.W, p.CORE_WIDTH))
  val fetch = Module(new FetchUnit())

  mem.io.reset := io.rst
  mem.io.if_mem.instrAddr := fetch.io.instr_addr
  fetch.io.instr := mem.io.mem_id

  val branch_predictor = Module(new BranchPredictorUnit())
  (branch_prodictor.io: Data).waiveAll :<>= (fetch.io: Data).waiveAll 

  val decode = Module(new DecodeUnit())
  (decode.io: Data).waiveAll :<>= (fetch.io: Data).waiveAll 

  val rename = Module(new RenameUnit())
  (rename.io: Data).waiveAll :<>= (decode.io: Data).waiveAll 

  val dispatch = Module(new DispatchUnit())
  (dispatch.io: Data).waiveAll :<>= (rename.io: Data).waiveAll 

  val iq_freelist = Module(new FreeList(UInt(log2Ceil(p.ISSUE_WIDTH).W), p.ISSUE_FREELIST_WIDTH, p.CORE_WIDTH))
  (iq_freelist.io: Data).waiveAll :<>= (dispatch.io: Data).waiveAll 

  val ld_issue = Module(new ld_issue_queue())
  val st_issue = Module(new st_issue_queue())
  val exu_issue = Module(new exu_issue_queue())
  (ld_issue.io: Data).waiveAll :<>= (dispatch.io: Data).waiveAll 
  (st_issue.io: Data).waiveAll :<>= (dispatch.io: Data).waiveAll 
  (exu_issue.io: Data).waiveAll :<>= (dispatch.io: Data).waiveAll 

  val rob = Module(new ROB())
  (rob.io: Data).waiveAll :<>= (dispatch.io: Data).waiveAll 
  (fetch.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  (rename.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  (dispatch.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  (ld_issue.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  (st_issue.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  (exu_issue.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 

  val lsu = 
  val exu = 

  val wb = 
}

object Core extends App {
  ChiselStage.emitSystemVerilogFile(
    new Core(),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
