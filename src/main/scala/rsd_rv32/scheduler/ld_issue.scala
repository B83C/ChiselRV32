package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.execution._

class ld_issue_IO(fu_num: Int)(implicit p: Parameters) extends CustomBundle {
    //来自Dispatch Unit的输入
    val ld_issue_uop = Flipped(Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_LDISSUE_uop()))))  //来自Dispatch Unit的输入

    //发射到ld的输出
    val load_uop = Decoupled(new LDISSUE_LDPIPE_uop())  //发射的指令

    //PRF
    val prf_raddr = Valid(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址1

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
    val ld_issued_index = (Valid(UInt(log2Ceil(p.LDISSUE_DEPTH).W))) //更新IQ Freelist

    //with ROB
    val rob_controlsignal = Flipped(new ROBControlSignal) //来自于ROB的控制信号
    
    // 来自st_issue的busy表
    val st_issue_busy_snapshot = Flipped(Vec(p.STISSUE_DEPTH, Bool()))
    // 在同组内（同周期发射）的store指令busy表
    val st_issue_busy_dispatch = Flipped(Vec(p.STISSUE_DEPTH, Bool()))

    // 更新ld_issue_content的st_busy
    val st_issued_index = Flipped(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))
}

class ld_issue_content(implicit p: Parameters) extends Bundle {
    val ps = UInt(log2Ceil(p.PRF_DEPTH).W) // 操作数的物理寄存器地址 

    val branch_mask = UInt(p.BRANCH_MASK_WIDTH.W)

    val waiting = Bool() // 表示有效
    val ps_ready = Bool() // 源操作数的ready信号
    val st_busy = Vec(p.STISSUE_DEPTH, Bool()) // 载入时是st_issue_queue内容busy表的快照，并由后续st_issued_index更新
}

class ld_issue_queue(fu_num: Int)(implicit p: Parameters) extends CustomModule {
    val io = IO(new ld_issue_IO(fu_num))
    val VALID = io.ld_issue_uop.valid
    val dis_uop = io.ld_issue_uop.bits

    val should_flush = io.rob_controlsignal.shouldFlush

    val issue_queue = RegInit(
        VecInit(Seq.fill(p.LDISSUE_DEPTH) (
            0.U.asTypeOf(new ld_issue_content())
        ))
    )

    val payload = RegInit(
        VecInit(Seq.fill(p.LDISSUE_DEPTH) (
            0.U.asTypeOf(new DISPATCH_LDISSUE_uop())
        ))
    )

    withReset(reset.asBool) {
        dis_uop.foreach { uop =>
            when(VALID && uop.valid && !should_flush)  {
                payload(uop.bits.iq_index) := uop.bits
                issue_queue(uop.bits.iq_index).waiting := true.B
                issue_queue(uop.bits.iq_index).ps := uop.bits.ps1
                issue_queue(uop.bits.iq_index).st_busy := VecInit((uop.bits.stq_mask | io.st_issue_busy_snapshot.asUInt).asBools)
                issue_queue(uop.bits.iq_index).branch_mask := uop.bits.branch_mask.asUInt

                // 因为ps可能在dispatch的时候就就绪了(信号来自WB)
                issue_queue(uop.bits.iq_index).ps_ready := uop.bits.ps1 === 0.U || !io.prf_busys(uop.bits.ps1) || io.wb_uop.exists(wb_uop => wb_uop.valid && wb_uop.bits.pdst.valid && wb_uop.bits.pdst.bits === uop.bits.ps1)
            }
        }
        issue_queue.foreach(iq => {
            when(iq.waiting && io.wb_uop.exists(wb_uop => wb_uop.valid && wb_uop.bits.pdst.valid && wb_uop.bits.pdst.bits === iq.ps)) {
                iq.ps_ready := true.B
            }
            when(io.st_issued_index.valid) {
                iq.st_busy(io.st_issued_index.bits) := false.B
            }
            when(io.rob_controlsignal.shouldBeKilled(iq.branch_mask)) {
                iq.waiting := false.B
            }
        })


        val ready_vec = VecInit(issue_queue.map(iq => iq.waiting && iq.ps_ready && !iq.st_busy.reduce(_ || _) ))
        val selected_entry = PriorityMux(ready_vec.zip(issue_queue))
        val selected_payload = PriorityMux(ready_vec.zip(payload))
        val selection_valid = ready_vec.asUInt =/= 0.U && !io.rob_controlsignal.shouldBeKilled(selected_entry.branch_mask)

        val operation_ready = selection_valid 
        val downstream_ready = io.load_uop.ready
        val ack = operation_ready && downstream_ready

        val sel_oh = PriorityEncoderOH(ready_vec)
        val sel_ind = OHToUInt(sel_oh) 
        when(ack) {
            issue_queue(sel_ind).waiting := false.B
        }

        val selected_payload_coerced = Wire(new LDISSUE_LDPIPE_uop)
        (selected_payload_coerced: Data).waiveAll :<>= (selected_payload: Data).waiveAll
        selected_payload_coerced.ps1_value := DontCare
        io.load_uop.valid := RegEnable(selection_valid, false.B, downstream_ready)
        io.load_uop.bits := RegEnable(selected_payload_coerced, ack)

        io.ld_issued_index.bits := OHToUInt(PriorityEncoderOH(ready_vec))
        io.ld_issued_index.valid := ack

        // 外接一个读取prf的模块
        io.prf_raddr.bits := RegEnable(selected_entry.ps, ack)
        io.prf_raddr.valid:= RegEnable(selection_valid, false.B, downstream_ready)
    }

    // Debugging
    // import chisel3.experimental.BundleLiterals._
    // io.load_uop.bits.debug(selected_payload, selection_valid)
}
