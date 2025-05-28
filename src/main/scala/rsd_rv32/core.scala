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
  // val mem = Module(new mem("../dhrystone.data_truncated", mem_depth, p.CORE_WIDTH))
  
  val fetch = Module(new FetchUnit)
  val branch_predictor = Module(new BranchPredictor)

  val exu = Module(new EXU)

  val decode = Module(new DecodeUnit)
  val rename = Module(new RenameUnit)
  val dispatch = Module(new DispatchUnit(exu.fus.length))
  val rob = Module(new ROB)

  val ld_issue = Module(new ld_issue_queue(p.FU_NUM))
  val st_issue = Module(new st_issue_queue(p.FU_NUM))
  val exu_issue = Module(new exu_issue_queue(p.FU_NUM, exu.fus_properties))

  val lsu = Module(new LSU)

  val prf_busys = RegInit(VecInit(Seq.fill(p.PRF_DEPTH)(false.B)))

  val wb_uops = VecInit(exu.io.wb_uop ++ Seq(lsu.io.ldu_wb_uop, lsu.io.stu_wb_uop, exu.io.serialised_wb_uop))

  dispatch.io.dis_uop.bits.foreach{ dispatched_uop => {
    when(dispatched_uop.valid && dispatched_uop.bits.pdst =/= 0.U) {
      prf_busys(dispatched_uop.bits.pdst) := true.B
    }
  }}
  wb_uops.foreach{wb_uop => {
    when(wb_uop.valid && wb_uop.bits.pdst_value.valid) {
      prf_busys(wb_uop.bits.pdst) := false.B
    }
  }}


  mem.io.reset := reset
  // Since if_mem is 4-byte aligned by default
  mem.io.if_mem.instAddr := fetch.io.instr_addr >> 2.U

  fetch.io.instr := mem.io.mem_id.inst
  fetch.io.predicted_next_pc := branch_predictor.io.predicted_next_pc 
  fetch.io.instr_mask := branch_predictor.io.instr_mask 
  fetch.io.should_branch := branch_predictor.io.should_branch 
  fetch.io.rob_controlsignal := rob.io.rob_controlsignal
  fetch.io.ghr := branch_predictor.io.ghr 

  branch_predictor.io.rob_controlsignal := rob.io.rob_controlsignal
  branch_predictor.io.rob_commitsignal := rob.io.rob_commitsignal
  branch_predictor.io.instr_addr := fetch.io.instr_addr 

  decode.io.id_uop :<>= fetch.io.id_uop
  decode.io.rob_controlsignal := rob.io.rob_controlsignal

  rename.io.rename_uop :<>= decode.io.rename_uop
  rename.io.rob_controlsignal := rob.io.rob_controlsignal
  rename.io.rob_commitsignal := rob.io.rob_commitsignal

  dispatch.io.dis_uop :<>= rename.io.dis_uop
  dispatch.io.rob_controlsignal := rob.io.rob_controlsignal

  // dispatch.io.stq_head := lsu.io.stq_head
  dispatch.io.stq_tail := lsu.io.stq_tail
  dispatch.io.stq_full := lsu.io.stq_full
  dispatch.io.rob_index := rob.io.rob_index
  dispatch.io.rob_empty := rob.io.rob_empty

  rob.io.rob_uop :<>= dispatch.io.rob_uop
  rob.io.wb_uop := wb_uops
  rob.io.bu_uop := exu.io.bu_signals

  ld_issue.io.prf_busys := prf_busys
  ld_issue.io.wb_uop := wb_uops
  ld_issue.io.st_issue_busy_snapshot := st_issue.io.st_issue_busy_snapshot
  // TODO
  ld_issue.io.st_issue_busy_dispatch := Mux(!dispatch.io.st_issue_uop.valid, 0.U, dispatch.io.st_issue_uop.bits.map(st_uop => Mux(!st_uop.valid, 0.U, (1.U(p.STISSUE_DEPTH.W) << st_uop.bits.iq_index)(p.STISSUE_DEPTH - 1, 0))).reduce(_ | _)).asBools
  ld_issue.io.rob_controlsignal := rob.io.rob_controlsignal
  ld_issue.io.st_issued_index := st_issue.io.st_issued_index

  st_issue.io.prf_busys := prf_busys
  st_issue.io.wb_uop := wb_uops
  st_issue.io.rob_controlsignal := rob.io.rob_controlsignal

  exu_issue.io.prf_busys := prf_busys
  exu_issue.io.wb_uop := wb_uops
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
  load_uop :<>= ld_issue.io.load_uop
  val store_uop = WireInit(st_issue.io.store_uop)
  store_uop :<>= st_issue.io.store_uop
  val serialised_uop = WireInit(dispatch.io.serialised_uop)
  val serialised_uop_exu = Wire(Valid(new EXUISSUE_EXU_uop))
  (serialised_uop_exu: Data).waiveAll :<>= (serialised_uop: Data).waiveAll

  val serialised_uop_prfs = Wire(Valid(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W))))
  serialised_uop_prfs.bits(0) := serialised_uop.bits.ps1
  serialised_uop_prfs.bits(1) := serialised_uop.bits.ps2
  serialised_uop_prfs.valid := serialised_uop.valid

  exu.io.execute_uop :<>= execute_uop
  exu.io.serialised_uop := serialised_uop_exu
  exu.io.rob_controlsignal := rob.io.rob_controlsignal

  lsu.io.load_uop :<>= load_uop
  lsu.io.store_uop.bits :<>= store_uop.bits
  lsu.io.store_uop.valid :<>= store_uop.valid
  lsu.io.st_inc := dispatch.io.st_inc
  lsu.io.rob_commitsignal := rob.io.rob_commitsignal
  lsu.io.rob_controlsignal := rob.io.rob_controlsignal

  val execute_prf_reads = (execute_uop.map(_.bits) :+ serialised_uop_exu.bits).zip(exu_issue.io.prf_raddr :+ serialised_uop_prfs).map{case (uop, prf_raddr) => {
    Seq(ReadValueRequest(uop.ps1_value, prf_raddr.bits(0), prf_raddr.valid )) ++  Seq(ReadValueRequest( uop.ps2_value, prf_raddr.bits(1), prf_raddr.valid))
  }}.reduce(_ ++ _)
  val load_prf_reads = Seq(load_uop.bits).zip(Seq(ld_issue.io.prf_raddr)).map{case (uop, prf_raddr) => {
    Seq(ReadValueRequest(uop.ps1_value, prf_raddr.bits, prf_raddr.valid))
  }}.reduce(_ ++ _)
  val store_prf_reads = Seq(store_uop.bits).zip(Seq(st_issue.io.prf_raddr)).map{case (uop, prf_raddr) => {
    Seq(ReadValueRequest( uop.ps1_value, prf_raddr.bits(0), prf_raddr.valid )) ++  Seq(ReadValueRequest( uop.ps2_value, prf_raddr.bits(1), prf_raddr.valid))
  }}.reduce(_ ++ _)

  val read_requests =
    (execute_prf_reads ++ load_prf_reads ++ store_prf_reads)
  val prf = Module(new PRF(read_requests.length))
  prf.io.read_requests :<>= VecInit(read_requests)
  prf.io.wb_uop := wb_uops
  prf.io.rob_controlsignal := rob.io.rob_controlsignal
  prf.io.rob_commitsignal := rob.io.rob_commitsignal

  //TODO 
  st_issue.io.store_uop.ready := true.B
  (mem.io.ex_mem: Data).waiveAll :<>= (lsu.io: Data).waiveAll
  lsu.io.data_out_mem := mem.io.mem_lsu.data_out_mem
  mem.io.ex_mem.atomFlag := DontCare
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
