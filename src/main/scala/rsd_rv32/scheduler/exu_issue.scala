package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.execution._

class exu_issue_IO(exu_fu_num: Int, fu_num: Int)(implicit p: Parameters) extends CustomBundle {
    //来自Dispatch Unit的输入
    val exu_issue_uop = Flipped(Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop()))))  //来自Dispatch Unit的输入

    //发射到ld的输出
    val execute_uop = Vec(exu_fu_num, Decoupled(new EXUISSUE_EXU_uop()))  //发射的指令

    // 来自EXU的ready信号
    // val readys = Flipped(Vec(exu_fu_num, Bool()))

    //PRF
    val prf_raddr = Vec(exu_fu_num, Valid(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W))))

    //监听PRF的valid信号用于更新ready状态
    // val prf_valid = Flipped(Vec(p.PRF_DEPTH, Bool())) //PRF的valid信号
//    //监听FU后级间寄存器内的物理寄存器ready信号
//    val wb_uop2 = Flipped((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Valid(new ALU_WB_uop()))))  //来自alu、mul、div、load pipeline的uop
    //val ldu_wb_uop2 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop
    //监听FU处物理寄存器的ready信号
    // TODO
    val wb_uop = Flipped(Vec(fu_num, Valid(new WB_uop()))) //来自所有FU的WB

    val prf_busys = Flipped(Vec(p.PRF_DEPTH, Bool())) // 表示在队列内busy的pdst

    //输出至Dispatch Unit的信号
    val exu_issued_index = Vec(exu_fu_num, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W))) //更新IQ Freelist

    //with ROB
    val rob_controlsignal = Flipped(Valid(new ROBControlSignal)) //来自于ROB的控制信号
}

class exu_issue_content(implicit p: Parameters) extends Bundle {
    val ps = Vec(2,UInt(log2Ceil(p.PRF_DEPTH).W)) // 操作数的物理寄存器地址 

    val instr_type = InstrType()
    val waiting = Bool() // 表示有效
    val ps_ready = Vec(2, Bool()) // 源操作数的ready信号
}

class exu_issue_queue(fu_num: Int, fus: EXU.InstrTypeSets)(implicit p: Parameters) extends CustomModule {
    val io = IO(new exu_issue_IO(fus.length, fu_num))
    val VALID = io.exu_issue_uop.valid
    val dis_uop = io.exu_issue_uop.bits

    val issue_queue = RegInit(
        VecInit(Seq.fill(p.EXUISSUE_DEPTH) (
            0.U.asTypeOf(new exu_issue_content())
        ))
    )

    val payload = RegInit(
        VecInit(Seq.fill(p.EXUISSUE_DEPTH) (
            0.U.asTypeOf(new DISPATCH_EXUISSUE_uop())
        ))
    )

    val flush = io.rob_controlsignal.valid && io.rob_controlsignal.bits.isMispredicted
    dis_uop.foreach { uop =>
        when(VALID && uop.valid)  {
            val uop_ps = VecInit(Seq(uop.bits.ps1, uop.bits.ps2))
            payload(uop.bits.iq_index) := uop.bits
            issue_queue(uop.bits.iq_index).waiting := true.B
            issue_queue(uop.bits.iq_index).ps := uop_ps
            issue_queue(uop.bits.iq_index).instr_type := uop.bits.instr_type

            // 因为ps可能在dispatch的时候就就绪了(信号来自WB)

            issue_queue(uop.bits.iq_index).ps_ready := VecInit(uop_ps.map(ps => ps === 0.U || !io.prf_busys(ps) || io.wb_uop.exists(wb_uop => wb_uop.valid && wb_uop.bits.pdst_value.valid && wb_uop.bits.pdst === ps)))
        }
    }

    import InstrType._

    var mask = 0.U(p.ISSUE_DEPTH.W)
    fus.reduce(_ ++ _).zip(io.prf_raddr).foreach{case (t, prf_raddr) => {
        val exu_port = EXU.get_mapping_of_fus_that_support(fus)(t)(io.execute_uop)
        val exu_issued_index_port = EXU.get_mapping_of_fus_that_support(fus)(t)(io.exu_issued_index)

        val ready_vec = VecInit(issue_queue.map(iq => iq.waiting && iq.ps_ready.reduce(_ && _) && iq.instr_type === t))
        exu_port.zip(exu_issued_index_port).foreach{case (exu_uop, issued_ind) =>
            val ready_vec_masked = ready_vec.asUInt & ~mask
            val sel_oh = PriorityEncoderOH(ready_vec_masked.asBools)
            val sel_ind = OHToUInt(sel_oh) 
            val selected_entry = issue_queue(sel_ind)
            val selected_payload = payload(sel_ind)
            val selection_valid = ready_vec_masked.asUInt =/= 0.U && exu_uop.ready

            val selected_payload_coerced = Wire(new EXUISSUE_EXU_uop)
            (selected_payload_coerced: Data).waiveAll :<>= (selected_payload: Data).waiveAll
            selected_payload_coerced.ps1_value := DontCare
            selected_payload_coerced.ps2_value := DontCare

            when(selection_valid) {
                issue_queue(sel_ind).waiting := false.B
            }

            exu_uop.valid := RegNext(selection_valid)
            exu_uop.bits := RegEnable(selected_payload_coerced, selection_valid)

            // Assuming that prf read happens asynchronously
            prf_raddr.bits := RegEnable(selected_entry.ps, selection_valid)
            prf_raddr.valid:= RegNext(selection_valid)

            // This is when freeing happens synchronously
            issued_ind.bits := sel_ind
            issued_ind.valid := selection_valid

            mask = mask | Mux(selection_valid, VecInit(sel_oh).asUInt, 0.U)

            // Debugging
            import chisel3.experimental.BundleLiterals._
            exu_uop.bits.debug(selected_payload, selection_valid)
        }
    }}
    
    // Eavesdrop on WB signals from all fus (including LSU)
    issue_queue.foreach(iq => {
        iq.ps.zip(iq.ps_ready).foreach{ case (ps, ps_ready) =>
            when(iq.waiting && io.wb_uop.exists(wb_uop => wb_uop.valid && wb_uop.bits.pdst_value.valid && wb_uop.bits.pdst === ps)) {
                ps_ready := true.B
            }
        }
    })

}
