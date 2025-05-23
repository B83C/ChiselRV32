package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.execution._

class st_issue_IO(fu_num: Int)(implicit p: Parameters) extends CustomBundle {
    //来自Dispatch Unit的输入
    val st_issue_uop = Flipped(Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_STISSUE_uop()))))  //来自Dispatch Unit的输入

    //发射到ld的输出
    val store_uop = Decoupled(new STISSUE_STPIPE_uop())  //发射的指令

    //PRF
    val prf_raddr = Valid(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W)))

    //监听PRF的valid信号用于更新ready状态
    val prf_valid = Flipped(Vec(p.PRF_DEPTH, Bool())) //PRF的valid信号
//    //监听FU后级间寄存器内的物理寄存器ready信号
//    val wb_uop2 = Flipped((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Valid(new ALU_WB_uop()))))  //来自alu、mul、div、load pipeline的uop
    //val ldu_wb_uop2 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop
    //监听FU处物理寄存器的ready信号
    // TODO
    val wb_uop = Flipped(Vec(fu_num, Valid(new WB_uop()))) //来自所有FU的WB

    //输出至Dispatch Unit的信号
    val st_issued_index = (Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W))) //更新IQ Freelist

    //with ROB
    val rob_controlsignal = Flipped(Valid(new ROBControlSignal)) //来自于ROB的控制信号

    // 来自st_issue的busy表
    val st_issue_busy_snapshot = (Vec(p.STISSUE_DEPTH, Bool()))
}

class st_issue_content(implicit p: Parameters) extends Bundle {
    val ps = Vec(2,UInt(log2Ceil(p.PRF_DEPTH).W)) // 操作数的物理寄存器地址 

    val busy = Bool() // 表示有效
    val ps_ready = Vec(2, Bool()) // 源操作数的ready信号
}

class st_issue_queue(fu_num: Int)(implicit p: Parameters) extends CustomModule {
    val io = IO(new st_issue_IO(fu_num))
    val VALID = io.st_issue_uop.valid
    val dis_uop = io.st_issue_uop.bits

    val issue_queue = RegInit(
        VecInit(Seq.fill(p.STISSUE_DEPTH) (
            0.U.asTypeOf(new st_issue_content())
        ))
    )

    val payload = RegInit(
        VecInit(Seq.fill(p.STISSUE_DEPTH) (
            0.U.asTypeOf(new DISPATCH_STISSUE_uop())
        ))
    )

    val flush = io.rob_controlsignal.valid && io.rob_controlsignal.bits.isMispredicted
    dis_uop.foreach { uop =>
        when(VALID && uop.valid)  {
            val uop_ps = VecInit(Seq(uop.bits.ps1, uop.bits.ps2))
            payload(uop.bits.iq_index) := uop.bits
            issue_queue(uop.bits.iq_index).busy := true.B
            issue_queue(uop.bits.iq_index).ps := uop_ps

            // 因为ps可能在dispatch的时候就就绪了(信号来自WB)

            issue_queue(uop.bits.iq_index).ps_ready := VecInit(uop_ps.map(ps => io.wb_uop.exists(wb_uop => wb_uop.valid && wb_uop.bits.pdst_value.valid && wb_uop.bits.pdst === ps)))
        }
    }
    issue_queue.foreach(iq => {
        iq.ps.zip(iq.ps_ready).foreach{ case (ps, ps_ready) =>
            when(iq.busy && io.wb_uop.exists(wb_uop => wb_uop.valid && wb_uop.bits.pdst_value.valid && wb_uop.bits.pdst === ps)) {
                ps_ready := true.B
            }
        }
    })

    val ready_vec = VecInit(issue_queue.map(iq => iq.busy && iq.ps_ready.reduce(_ && _)))
    val selected_entry = PriorityMux(ready_vec.zip(issue_queue))
    val selected_payload = PriorityMux(ready_vec.zip(payload))
    val selection_valid = ready_vec.asUInt =/= 0.U

    val selected_payload_coerced = Wire(new STISSUE_STPIPE_uop)
    (selected_payload_coerced: Data).waiveAll :<>= (selected_payload: Data).waiveAll
    selected_payload_coerced.ps1_value := DontCare
    selected_payload_coerced.ps2_value := DontCare
    io.store_uop.valid := RegNext(selection_valid)
    io.store_uop.bits := RegEnable(selected_payload_coerced, selection_valid)

    io.st_issued_index.bits := OHToUInt(PriorityEncoderOH(ready_vec))
    io.st_issued_index.valid := selection_valid

    // 外接一个读取prf的模块
    io.prf_raddr.bits := selected_entry.ps
    io.prf_raddr.valid := selection_valid

    io.st_issue_busy_snapshot := issue_queue.map(_.busy)

    // Debugging
    import chisel3.experimental.BundleLiterals._
    io.store_uop.bits.debug := RegNext(Mux(selection_valid, selected_payload.debug, 0.U.asTypeOf(new InstrDebug)))
}
