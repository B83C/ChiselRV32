package rsd_rv32

import chisel3._
import chisel3.util._
import chisel3._
import chisel3.experimental.VecLiterals._
import _root_.circt.stage.ChiselStage

import rsd_rv32.common._
import rsd_rv32.scheduler._
import rsd_rv32.frontend._
import rsd_rv32.execution._
import java.lang.reflect.Parameter

// Top Level Structure
class Machine() extends Module {
  implicit val p: Parameters = new Parameters
  val core = Module(new Core)

  val off :: running :: stopped :: Nil = Enum(3)
  val current_state = RegInit(off)
  val start_up_counter = Counter(4)
  when(current_state === off) {
    core.reset := true.B
    when(start_up_counter.value >= 3.U) {
      current_state := running 
    }
    start_up_counter.inc()
  }.elsewhen(current_state === running) {
    println("Core started!")
    core.reset := false.B
  }
}

class Core(implicit p: Parameters) extends CustomModule {
  val mem_depth = 512
  // val mem_depth = 12376
  val mem = Module(new mem("../drystone.data_truncated", mem_depth, p.CORE_WIDTH))
  mem.io.mem_lsu := DontCare
  mem.io.ex_mem := DontCare
  
  val fetch = Module(new FetchUnit)
  val branch_predictor = Module(new BranchPredictorUnit)

  val decode = Module(new DecodeUnit)
  val rename = Module(new RenameUnit)
  val dispatch = Module(new DispatchUnit)
  val rob = Module(new ROB)

  val ld_issue = Module(new ld_issue_queue)
  val st_issue = Module(new st_issue_queue)
  val exu_issue = Module(new exu_issue_queue)

  val prf = Module(new PRF)


  mem.io.reset := reset
  // Since if_mem is 4-byte aligned by default
  mem.io.if_mem.instAddr := fetch.io.instr_addr >> 2.U

  fetch.io.instr := mem.io.mem_id.inst
  fetch.io.id_ready := decode.io.id_ready
  fetch.io.rob_commitsignal := rob.io.rob_commitsignal
  fetch.io.target_PC := branch_predictor.io.target_PC 
  fetch.io.btb_hit := branch_predictor.io.btb_hit 
  fetch.io.branch_pred := branch_predictor.io.branch_pred.reduce(_ || _)
  fetch.io.GHR := branch_predictor.io.GHR 

  branch_predictor.io.rob_commitsignal := rob.io.rob_commitsignal
  branch_predictor.io.instr_addr := fetch.io.instr_addr 

  decode.io.id_uop := fetch.io.id_uop
  decode.io.rename_ready := rename.io.rename_ready
  decode.io.rob_commitsignal := rob.io.rob_commitsignal

  rename.io.rename_uop := decode.io.rename_uop
  rename.io.dis_ready := dispatch.io.dis_uop.ready
  rename.io.rob_commitsignal := rob.io.rob_commitsignal

  dispatch.io.dis_uop.bits := rename.io.dis_uop
  dispatch.io.dis_uop.valid := rename.io.dis_ready

  val dummy = Wire(Vec(p.CORE_WIDTH, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W))))
  dummy := DontCare 
  dummy(0).valid := false.B
  dummy(1).valid := false.B
  // dispatch.io := DontCare
  dispatch.io.stq_head := 0.U
  dispatch.io.stq_tail := 0.U
  dispatch.io.stq_full := 0.U
  dispatch.io.rob_head := rob.io.rob_head
  dispatch.io.rob_tail := rob.io.rob_tail
  dispatch.io.rob_full := rob.io.rob_full
  dispatch.io.rob_commitsignal := rob.io.rob_commitsignal

  rob.io := DontCare 
  rob.io.rob_uop := dispatch.io.rob_uop
  // val prf = Module(new PRF)
  // ld_issue.io.ld_issue_uop := dispatch.io.ld_issue_uop
  // st_issue.io.st_issue_uop := dispatch.io.st_issue_uop
  // exu_issue.io.exu_issue_uop := dispatch.io.exu_issue_uop
  ld_issue.io := DontCare
  ld_issue.io.load_uop.ready := true.B
  exu_issue.io.prf_valid := prf.io.prf_valid
  ld_issue.io.prf_value := prf.io.ld_issue_r_value1
  ld_issue.io.rob_commitsignal := rob.io.rob_commitsignal

  st_issue.io := DontCare
  exu_issue.io.prf_valid := prf.io.prf_valid
  st_issue.io.ps1_value := prf.io.st_issue_r_value1
  st_issue.io.ps2_value := prf.io.st_issue_r_value2
  st_issue.io.rob_commitsignal := rob.io.rob_commitsignal

  exu_issue.io := DontCare
  exu_issue.io.prf_valid := prf.io.prf_valid
  exu_issue.io.ps1_value := prf.io.exu_issue_r_value1
  exu_issue.io.ps2_value := prf.io.exu_issue_r_value2
  exu_issue.io.rob_commitsignal := rob.io.rob_commitsignal

  ld_issue.io.ld_issue_uop <> dispatch.io.ld_issue_uop
  st_issue.io.st_issue_uop <> dispatch.io.st_issue_uop
  exu_issue.io.exu_issue_uop <> dispatch.io.exu_issue_uop
  ld_issue.io.ld_issued_index <> dispatch.io.ld_issued_index
  st_issue.io.st_issued_index <> dispatch.io.st_issued_index
  exu_issue.io.exu_issued_index <> dispatch.io.exu_issued_index

  prf.io := DontCare
  prf.io.amt := rename.io.amt
  prf.io.exu_issue_r_addr1 := exu_issue.io.prf_raddr1
  prf.io.exu_issue_r_addr2 := exu_issue.io.prf_raddr2
  prf.io.st_issue_r_addr1 := st_issue.io.prf_raddr1
  prf.io.st_issue_r_addr2 := st_issue.io.prf_raddr2
  prf.io.ld_issue_r_addr1 := ld_issue.io.prf_raddr
  prf.io.rob_commitsignal := rob.io.rob_commitsignal

  // (rob.io: Data).waiveAll :<>= (dispatch.io: Data).waiveAll 
  // (branch_predictor.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  // (fetch.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  // (rename.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  // (dispatch.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  // (ld_issue.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  // (st_issue.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 
  // (exu_issue.io: Data).waiveAll :<>= (rob.io: Data).waiveAll 

  // val lsu = Module(new LSU())
  // (lsu.io: Data).waiveAll :<>= (dispatch.io: Data).waiveAll 
  // (lsu.io: Data).waiveAll :<>= (ld_issue.io: Data).waiveAll 
  // (lsu.io: Data).waiveAll :<>= (st_issue.io: Data).waiveAll 
  // (mem.io.ex_mem: Data).waiveAll :<>= (lsu.io: Data).waiveAll 
  // (lsu.io: Data).waiveAll :<>= (mem.io.mem_lsu: Data).waiveAll 

  // val exu = Module(new EXU()) 
  // (exu_issue.io: Data).waiveAll :<>= (exu.io: Data).waiveAll 
  // (ld_issue.io: Data).waiveAll :<>= (exu.io: Data).waiveAll 
  // (st_issue.io: Data).waiveAll :<>= (exu.io: Data).waiveAll 

  // val prf = Module(new PRF())
}

object Machine extends App {
  ChiselStage.emitSystemVerilogFile(
    new Machine(),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-O=debug",
      // "--preserve-values=all",
      // "--lowering-options=disallowLocalVariables",
    )
  )
}
