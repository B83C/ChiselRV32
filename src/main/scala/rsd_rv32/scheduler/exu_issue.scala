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
    //PRF
    val prf_raddr = Vec(exu_fu_num, Valid(Vec(2, UInt(log2Ceil(p.PRF_DEPTH).W))))

    // TODO
    val wb_uop = Flipped(Vec(fu_num, Valid(new WB_uop()))) //来自所有FU的WB

    val prf_busys = Flipped(Vec(p.PRF_DEPTH, Bool())) // 表示在队列内busy的pdst

    //输出至Dispatch Unit的信号
    val exu_issued_index = Vec(exu_fu_num, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W))) //更新IQ Freelist

    //with ROB
    val rob_controlsignal = Flipped(new ROBControlSignal) //来自于ROB的控制信号
}

class exu_issue_content(implicit p: Parameters) extends Bundle {
    val ps = Vec(2,UInt(log2Ceil(p.PRF_DEPTH).W)) // 操作数的物理寄存器地址 

    val branch_mask = UInt(p.BRANCH_MASK_WIDTH.W)

    val instr_type = InstrType()
    val waiting = Bool() // 表示有效
    val ps_ready = Vec(2, Bool()) // 源操作数的ready信号
}

class exu_issue_queue(fu_num: Int, fus_props: Seq[FUProps])(implicit p: Parameters) extends CustomModule {
    val io = IO(new exu_issue_IO(fus_props.length, fu_num))
    val VALID = io.exu_issue_uop.valid
    val dis_uop = io.exu_issue_uop.bits

    val should_flush = io.rob_controlsignal.shouldFlush

    withReset(reset.asBool) {
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


        dis_uop.foreach { uop =>
            when(VALID && uop.valid && !should_flush)  {
                val uop_ps = VecInit(Seq(uop.bits.ps1, uop.bits.ps2))
                val uop_ps_is_reg = VecInit(Seq(uop.bits.opr1_sel, uop.bits.opr2_sel).map(_ === OprSel.REG))
                payload(uop.bits.iq_index) := uop.bits
                issue_queue(uop.bits.iq_index).waiting := true.B
                issue_queue(uop.bits.iq_index).ps := uop_ps
                issue_queue(uop.bits.iq_index).instr_type := uop.bits.instr_type
                issue_queue(uop.bits.iq_index).branch_mask := uop.bits.branch_mask.asUInt

                // 因为ps可能在dispatch的时候就就绪了(信号来自WB)

                issue_queue(uop.bits.iq_index).ps_ready := VecInit(uop_ps.zip(uop_ps_is_reg).map{case (ps, is_reg) => !is_reg || !io.prf_busys(ps) || io.wb_uop.exists(wb_uop => wb_uop.valid && wb_uop.bits.pdst.valid && wb_uop.bits.pdst.bits === ps)})
            }
        }

        var mask = 0.U(p.ISSUE_DEPTH.W)
        // fus_props.map(x => x.supportedInstr).foreach{case instr_types => {
        io.execute_uop.zip(io.exu_issued_index).zip(io.prf_raddr).zip(fus_props).foreach{case (((exu_uop, issued_ind), prf_raddr), fu_prop) =>
            val ready_vec = VecInit(issue_queue.map(iq => iq.waiting && iq.ps_ready.reduce(_ && _) && iq.instr_type.isOneOf(fu_prop.supportedInstr.toSeq)))
            val ready_vec_masked = ready_vec.asUInt & ~mask
            val sel_oh = PriorityEncoderOH(ready_vec_masked.asBools)
            val sel_ind = OHToUInt(sel_oh) 
            val selected_entry = issue_queue(sel_ind)
            val selected_payload = payload(sel_ind)
            val selection_valid = ready_vec_masked.asUInt =/= 0.U && exu_uop.ready && !io.rob_controlsignal.shouldBeKilled(selected_entry.branch_mask)

            val selected_payload_coerced = Wire(new EXUISSUE_EXU_uop)
            (selected_payload_coerced: Data).waiveAll :<>= (selected_payload: Data).waiveAll
            selected_payload_coerced.ps1_value := DontCare
            selected_payload_coerced.ps2_value := DontCare

            when(selection_valid) {
                selected_entry.waiting := false.B
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
            // exu_uop.bits.debug(selected_payload, selection_valid)
        }
        // }}
    
        // Eavesdrop on WB signals from all fus (including LSU)
        issue_queue.foreach(iq => {
            iq.ps.zip(iq.ps_ready).foreach{ case (ps, ps_ready) =>
                when(iq.waiting && io.wb_uop.exists(wb_uop => wb_uop.valid && wb_uop.bits.pdst.valid && wb_uop.bits.pdst.bits === ps)) {
                    ps_ready := true.B
                }
            }
            when(io.rob_controlsignal.shouldBeKilled(iq.branch_mask)) {
                iq.waiting := false.B
            }
        })
    }
}
