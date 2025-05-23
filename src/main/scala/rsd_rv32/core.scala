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
import rsd_rv32.common.Utils.ReadValueRequest

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
  val mem = Module(new mem("../dhrystone.data_truncated", mem_depth, p.CORE_WIDTH))
  mem.io.mem_lsu := DontCare
  mem.io.ex_mem := DontCare
  
  val fetch = Module(new FetchUnit)
  val branch_predictor = Module(new BranchPredictorUnit)

  val exu = Module(new EXU)

  val decode = Module(new DecodeUnit)
  val rename = Module(new RenameUnit)
  val dispatch = Module(new DispatchUnit(exu.fus.length))
  // dispatch.io := DontCare
  val rob = Module(new ROB)


  val ld_issue = Module(new ld_issue_queue(p.FU_NUM))
  val st_issue = Module(new st_issue_queue(p.FU_NUM))
  val exu_issue = Module(new exu_issue_queue(p.FU_NUM, exu.fus_mapping))

  // val lsu = Module(new LSU)


  mem.io.reset := reset
  // Since if_mem is 4-byte aligned by default
  mem.io.if_mem.instAddr := fetch.io.instr_addr >> 2.U

  fetch.io.instr := mem.io.mem_id.inst
  fetch.io.id_ready := decode.io.id_ready
  // TODO: won't work but had to make it compile first
  fetch.io.target_PC := branch_predictor.io.target_PC 
  fetch.io.btb_hit := branch_predictor.io.btb_hit 
  fetch.io.branch_pred := branch_predictor.io.branch_pred.reduce(_ || _)
  fetch.io.rob_controlsignal := rob.io.rob_controlsignal
  fetch.io.GHR := branch_predictor.io.GHR 

  branch_predictor.io.rob_controlsignal := rob.io.rob_controlsignal
  branch_predictor.io.rob_commitsignal := rob.io.rob_commitsignal
  branch_predictor.io.instr_addr := fetch.io.instr_addr 

  decode.io.id_uop := fetch.io.id_uop
  decode.io.rename_ready := rename.io.rename_ready
  decode.io.rob_controlsignal := rob.io.rob_controlsignal

  rename.io.rename_uop := decode.io.rename_uop
  rename.io.dis_ready := dispatch.io.dis_uop.ready
  rename.io.rob_controlsignal := rob.io.rob_controlsignal
  rename.io.rob_commitsignal := rob.io.rob_commitsignal

  dispatch.io.dis_uop.bits := rename.io.dis_uop
  dispatch.io.dis_uop.valid := rename.io.dis_ready
  dispatch.io.rob_controlsignal := rob.io.rob_controlsignal

  val dummy_wb = Wire(Valid(new WB_uop))
  dummy_wb := DontCare 
  val dummy = Wire(Vec(p.CORE_WIDTH, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W))))
  dummy := DontCare 
  dummy(0).valid := false.B
  dummy(1).valid := false.B
  // dispatch.io := DontCare
  dispatch.io.stq_head := 0.U
  dispatch.io.stq_tail := 0.U
  dispatch.io.stq_full := 0.U
  dispatch.io.rob_index := rob.io.rob_index
  dispatch.io.rob_empty := rob.io.rob_empty

  rob.io.rob_uop :<>= dispatch.io.rob_uop
  rob.io.wb_uop := VecInit(exu.io.wb_uop ++ Seq(dummy_wb, dummy_wb, exu.io.serialised_wb_uop))
  rob.io.bu_uop := exu.io.bu_signals

  val prf_valid = Wire(Vec(p.PRF_DEPTH, Bool()))

  ld_issue.io.load_uop.ready := true.B
  ld_issue.io.prf_valid := prf_valid
  ld_issue.io.wb_uop := VecInit(exu.io.wb_uop ++ Seq(dummy_wb, dummy_wb, exu.io.serialised_wb_uop))
  ld_issue.io.st_issue_busy_snapshot := st_issue.io.st_issue_busy_snapshot
  // TODO
  ld_issue.io.st_issue_busy_dispatch := Mux(dispatch.io.st_issued_index.valid, (1.U(p.STISSUE_DEPTH.W) << dispatch.io.st_issued_index.bits)(p.STISSUE_DEPTH -1, 0), 0.U).asBools
  ld_issue.io.rob_controlsignal := rob.io.rob_controlsignal
  ld_issue.io.st_issued_index := st_issue.io.st_issued_index

  st_issue.io.store_uop.ready := true.B
  st_issue.io.prf_valid := prf_valid
  st_issue.io.wb_uop := VecInit(exu.io.wb_uop ++ Seq(dummy_wb, dummy_wb, exu.io.serialised_wb_uop))
  st_issue.io.rob_controlsignal := rob.io.rob_controlsignal

  exu_issue.io.prf_valid := prf_valid
  exu_issue.io.wb_uop := VecInit(exu.io.wb_uop ++ Seq(dummy_wb, dummy_wb, exu.io.serialised_wb_uop))
  exu_issue.io.rob_controlsignal := rob.io.rob_controlsignal

  ld_issue.io.ld_issue_uop :<>= dispatch.io.ld_issue_uop
  st_issue.io.st_issue_uop :<>= dispatch.io.st_issue_uop
  exu_issue.io.exu_issue_uop :<>= dispatch.io.exu_issue_uop

  dispatch.io.ld_issued_index := ld_issue.io.ld_issued_index
  dispatch.io.st_issued_index := st_issue.io.st_issued_index
  dispatch.io.exu_issued_index := exu_issue.io.exu_issued_index

  //Hacks
  val execute_uop = WireInit(exu_issue.io.execute_uop)
  execute_uop :<>= exu_issue.io.execute_uop
  val load_uop = WireInit(ld_issue.io.load_uop)
  val store_uop = WireInit(st_issue.io.store_uop)
  val serialised_uop = WireInit(dispatch.io.serialised_uop)
  val serialised_uop_exu = Wire(Valid(new EXUISSUE_EXU_uop))
  (serialised_uop_exu: Data).waiveAll :<>= (serialised_uop: Data).waiveAll

  val serialised_uop_prfs = Wire(Valid(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W))))
  serialised_uop_prfs.bits(0) := serialised_uop.bits.ps1
  serialised_uop_prfs.bits(1) := serialised_uop.bits.ps2
  serialised_uop_prfs.valid := serialised_uop.valid

  val execute_prf_reads = (execute_uop.map(_.bits) :+ serialised_uop_exu.bits).zip(exu_issue.io.prf_raddr :+ serialised_uop_prfs).map{case (uop, prf_raddr) => {
    Seq(ReadValueRequest( uop.ps1_value, prf_raddr.bits(0), prf_raddr.valid )) ++  Seq(ReadValueRequest( uop.ps2_value, prf_raddr.bits(1), prf_raddr.valid))
  }}.reduce(_ ++ _)
  val load_prf_reads = VecInit(load_uop.bits).zip(VecInit(ld_issue.io.prf_raddr)).map{case (uop, prf_raddr) => {
    Seq(ReadValueRequest(uop.ps1_value, prf_raddr.bits, prf_raddr.valid))
  }}.reduce(_ ++ _)
  val store_prf_reads = VecInit(store_uop.bits).zip(VecInit(st_issue.io.prf_raddr)).map{case (uop, prf_raddr) => {
    Seq(ReadValueRequest( uop.ps1_value, prf_raddr.bits(0), prf_raddr.valid )) ++  Seq(ReadValueRequest( uop.ps2_value, prf_raddr.bits(1), prf_raddr.valid))
  }}.reduce(_ ++ _)

  val read_requests =
    (execute_prf_reads ++ load_prf_reads ++ store_prf_reads)
  val prf = Module(new PRF(read_requests.length))
  prf.io.read_requests :<>= VecInit(read_requests)
  prf.io.amt := rename.io.amt
  prf.io.wb_uop := VecInit(exu.io.wb_uop ++ Seq(dummy_wb, dummy_wb, exu.io.serialised_wb_uop))
  prf.io.rob_controlsignal := rob.io.rob_controlsignal
  prf.io.rob_commitsignal := rob.io.rob_commitsignal
  prf_valid := prf.io.prf_valid

  exu.io.execute_uop :<>= execute_uop
  exu.io.serialised_uop := serialised_uop_exu
  exu.io.rob_controlsignal := rob.io.rob_controlsignal
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
