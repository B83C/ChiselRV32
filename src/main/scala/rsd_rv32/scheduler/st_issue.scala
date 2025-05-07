package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class st_issue_IO(implicit p: Parameters) extends CustomBundle {
    //来自Dispatch Unit的输入
    //val iq_id = Input(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //IQ ID
    val dis_uop = Flipped(Vec(p.CORE_WIDTH, Valid(new DISPATCH_STISSUE_uop())))  //来自Dispatch Unit的输入

    //发射到st的输出
    val issue_st_uop = Decoupled(new STISSUE_STPIPE_uop())  //发射的指令
    // val value_o1 = Output(UInt(p.XLEN.W)) //发射的指令的操作数1
    // val value_o2 = Output(UInt(p.XLEN.W)) //发射的指令的操作数2

    //PRF
    val prf_raddr1 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址1
    val prf_raddr2 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址2
    val prf_value1 = Input(UInt(p.XLEN.W)) //操作数1
    val prf_value2 = Input(UInt(p.XLEN.W)) //操作数2    

    //监听PRF的valid信号用于更新ready状态
    val prf_valid = Input(Vec(p.PRF_DEPTH, Bool())) //PRF的valid信号
    //监听FU后级间寄存器内的物理寄存器ready信号
    val wb_uop2 = Flipped(Valid((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, new ALU_WB_uop()))))  //来自alu、mul、div、load pipeline的uop
    //val LDU_complete_uop2 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop
    //监听FU处物理寄存器的ready信号
    val wb_uop1 = Flipped(Valid((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, new ALU_WB_uop()))))  //来自alu、mul、div、load pipeline的uop
    //val LDU_complete_uop1 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop

    //输出至Dispatch Unit的信号
    val st_issued_index = Output(UInt(log2Ceil(p.STISSUE_DEPTH).W)) //更新IQ Freelist

    //with ROB
    val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷

    //To ld_issue_queue
    val st_queue_state = Output(Vec(p.STISSUE_DEPTH, Bool()))
}

class st_iq_select_logic(implicit p: Parameters) extends Module{
    val io = IO(new Bundle {
        val issue_queue = Input(Vec(p.STISSUE_DEPTH, new st_issue_content()))
        val sel_index = Output(Vec(2, Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))) //选择的索引
    })
    val readySt = VecInit((0 until p.STISSUE_DEPTH).map { i =>
        val q = io.issue_queue(i)
        q.busy && q.ready1 && q.ready2
    })
    val stOH1 = PriorityEncoderOH(readyAlu)
    val stIdx1 = OHToUInt(aluOH1)
    val stV1 = readyAlu.asUInt.orR

    val stMasked = Wire(Vec(p.EXUISSUE_DEPTH, Bool()))
    for (i <- 0 until p.EXUISSUE_DEPTH) {
        stMasked(i) := readySt(i) && !stOH1(i)
    }
    val stOH2 = PriorityEncoderOH(aluMasked2)
    val stIdx2 = OHToUInt(aluOH2)
    val stV2 = stMasked.asUInt.orR

    when(stV1) {
        io.sel_index(0).valid := true.B
        io.sel_index(0).bits := stIdx1
    }.otherwise {
        io.sel_index(0).valid := false.B
        io.sel_index(0).bits := 0.U
    }

    when(stV2) {
        io.sel_index(1).valid := true.B
        io.sel_index(1).bits := stIdx2
    }.otherwise {
        io.sel_index(1).valid := false.B
        io.sel_index(1).bits := 0.U
    }
}

//exu_issue->st的级间寄存器
class issue2st(implicit p: Parameters) extends Module {
    val io = IO(new Bundle {
        val if_valid =Input(Vec(p.CORE_WIDTH, Bool())) //指令是否有效
        val ps1_value = Input(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //操作数1
        val ps2_value = Input(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //操作数2
        val dis_issue_uop = Input(Vec(p.CORE_WIDTH, new DISPATCH_STISSUE_uop())) //来自Dispatch的uop
        val issue_st_uop = Output(Vec(p.CORE_WIDTH, Valid(new STISSUE_EXU_uop()))) //发往EXU的uop
    })
    val uop = Reg(Vec(p.CORE_WIDTH, Valid(new STISSUE_STPIPE_uop())))
    for (i <-0 until p.CORE_WIDTH){
        uop(i).valid := io.if_valid(i)
        uop(i).bits.instr := io.dis_issue_uop(i).instr
        uop(i).bits.ps1_value := io.ps1_value(i)
        uop(i).bits.ps2_value := io.ps2_value(i)
        uop(i).bits.stq_index := io.dis_issue_uop(i).stq_index
        uop(i).bits.rob_index := io.dis_issue_uop(i).rob_index
    }
    io.issue_st_uop := uop
}


class st_issue_content(implicit p: Parameters) extends Module{
    val busy = Bool() //busy bit
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W) //物理寄存器地址1
    val ready1 = Bool() //物理寄存器1的ready信号
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W) //物理寄存器地址2
    val ready2 = Bool() //物理寄存器2的ready信号
}

class st_issue_queue(implicit p: Parameters) extends Module {
    val io = IO(new st_issue_IO())
    //存储结构定义
    val issue_queue = RegInit(
        VecInit(Seq.fill(p.EXUISSUE_DEPTH) {
            0.U.asTypeOf(new st_issue_content())
        })
    )
    val payload = RegInit(
        VecInit(Seq.fill(p.EXUISSUE_DEPTH) {
            0.U.asTypeOf(new DISPATCH_STISSUE_uop())
        })
    )
    //存储结构定义结束

    //初始化
    io.issue_st_uop := 0.U.asTypeOf(Vec(p.CORE_WIDTH, Valid(new STISSUE_STPIPE_uop())))
    io.exu_issued_index := 0.U.asTypeOf(Vec(p.CORE_WIDTH, Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W))))
    io.prf_raddr1 := 0.U.asTypeOf(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.PRF_DEPTH).W)))
    io.prf_raddr2 := 0.U.asTypeOf(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.PRF_DEPTH).W)))
    //判断是否flush
    when(io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred){
        issue_queue(i).busy := false.B
        issue_queue(i).ready1 := false.B
        issue_queue(i).ready2 := false.B
    }.otherwise{
        //Dispatch入队命令
        when(io.dis_uop(0).valid && !io.dis_uop(1).valid){
            payload(io.dis_uop(0).bits.iq_index) := io.dis_uop(0).bits
            issue_queue(io.dis_uop(0).bits.iq_index).busy := true.B
            issue_queue(io.dis_uop(0).bits.iq_index).ps1 := io.dis_uop(0).bits.ps1
            issue_queue(io.dis_uop(0).bits.iq_index).ps2 := io.dis_uop(0).bits.ps2

            //入队时判断ps1和ps2的ready信号
            val conditions1 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === io.dis_uop(0).bits.ps1) ||
                (io.wb_uop2(i).valid && io.wb_uop2(i).bits.pdst === io.dis_uop(0).bits.ps1)
            }
            val conditions2 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                io.prf_valid(io.dis_uop(0).bits.ps2) ||
                (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === io.dis_uop(0).bits.ps2) ||
                (io.wb_uop2(i).valid && io.wb_uop2(i).bits.pdst === io.dis_uop(0).bits.ps2)
            }
            when (conditions1.reduce(_ || _) || io.prf_valid(io.dis_uop(0).bits.ps1) || (io.dis_uop(0).bits.fu_signals.opr1_sel =/= OprSel.REG)) {
                issue_queue(io.dis_uop(0).bits.iq_index).ready1 := true.B
            }
            when (conditions2.reduce(_ || _) || io.prf_valid(io.dis_uop(0).bits.ps2) || (io.dis_uop(0).bits.fu_signals.opr2_sel =/= OprSel.REG)) {
                issue_queue(io.dis_uop(0).bits.iq_index).ready2 := true.B
            }
            //结束ready信号赋值
        } .elsewhen(io.dis_uop(0).valid && io.dis_uop(1).valid){
            for (k <- 0 until 2){
                payload(io.dis_uop(k).bits.iq_index) := io.dis_uop(k).bits
                issue_queue(io.dis_uop(k).bits.iq_index).busy := true.B
                issue_queue(io.dis_uop(k).bits.iq_index).instr_type := io.dis_uop(k).bits.instr_type
                issue_queue(io.dis_uop(k).bits.iq_index).ps1 := io.dis_uop(k).bits.ps1
                issue_queue(io.dis_uop(k).bits.iq_index).ps2 := io.dis_uop(k).bits.ps2

                //入队时判断ps1和ps2的ready信号
                val conditions1 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                    (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === io.dis_uop(k).bits.ps1) ||
                    (io.wb_uop2(i).valid && io.wb_uop2(i).bits.pdst === io.dis_uop(k).bits.ps1)
                }
                val conditions2 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                    (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === io.dis_uop(k).bits.ps2) ||
                    (io.wb_uop2(i).valid && io.wb_uop2(i).bits.pdst === io.dis_uop(k).bits.ps2)
                }
                when (conditions1.reduce(_ || _) || io.prf_valid(io.dis_uop(k).bits.ps1) || (io.dis_uop(k).bits.fu_signals.opr1_sel =/= OprSel.REG)) {
                    issue_queue(io.dis_uop(k).bits.iq_index).ready1 := true.B
                }
                when (conditions2.reduce(_ || _) || io.prf_valid(io.dis_uop(k).bits.ps2) || (io.dis_uop(k).bits.fu_signals.opr2_sel =/= OprSel.REG)) {
                    issue_queue(io.dis_uop(k).bits.iq_index).ready2 := true.B
                }
                //结束ready信号赋值
            }
        }.otherwise{
            //不入队
        }

        //每周期监听后续寄存器的ready信号，更新ready状态
        for (i <- 0 until p.LDISSUE_DEPTH){
            when (issue_queue(i).busy){
                val update_conditions1 = for (j <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                    (io.wb_uop1(j).valid && io.wb_uop1(j).bits.pdst === issue_queue(i).ps1) ||
                    (io.wb_uop2(j).valid && io.wb_uop2(j).bits.pdst === issue_queue(i).ps1)
                }
                val update_conditions2 = for (j <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                    (io.wb_uop1(j).valid && io.wb_uop1(j).bits.pdst === issue_queue(i).ps2) ||
                    (io.wb_uop2(j).valid && io.wb_uop2(j).bits.pdst === issue_queue(i).ps2)
                }
                when (update_conditions1.reduce(_ || _) || io.prf_valid(issue_queue(i).ps1)) {
                    issue_queue(i).ready1 := true.B
                }
                when (update_conditions2.reduce(_ || _) || io.prf_valid(issue_queue(i).ps2)) {
                    issue_queue(i).ready2 := true.B
                }
            }
        }

        //Select Logic
        val select_logic = Module(new st_iq_select_logic())
        val select_index = Wire(Vec(2, Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W))))
        select_logic.io.issue_queue := issue_queue
        select_index := select_logic.io.sel_index

        //读PRF
        for (i <- 0 until 2){
            when (select_index(i).valid){
                io.prf_raddr1(i) := issue_queue(select_index(i).bits).ps1
                io.prf_raddr2(i) := issue_queue(select_index(i).bits).ps2
            }.otherwise{
                io.prf_raddr1(i) := 0.U
                io.prf_raddr2(i) := 0.U
            }
        }

        //更新IQ Freelist的信号
        for (i <- 0 until 2){
            when (select_index(i).valid){
                io.st_issued_index(i).valid := true.B
                io.st_issued_index(i).bits := select_index(i).bits
            }.otherwise{
                io.st_issued_index(i).valid := false.B
                io.st_issued_index(i).bits := 0.U
            }
        }

        //发射命令到级间寄存器
        val issue_to_st = Module(new issue2st())
        for (i <- 0 until 2){
            when (select_index(i).valid){
                issue_to_st.io.if_valid(i) := true.B
                issue_queue(select_index(i).bits).busy := false.B
                issue_queue(select_index(i).bits).ready1 := false.B
                issue_queue(select_index(i).bits).ready2 := false.B
            }.otherwise{
                issue_to_st.io.if_valid(i) := false.B
            }
            issue_to_st.io.dis_issue_uop(i) := payload(select_index(i).bits)
            issue_to_st.io.ps1_value(i) := io.ps1_value(i)
            issue_to_st.io.ps2_value(i) := io.ps2_value(i)
            io.issue_st_uop(i) := issue_to_st.io.issue_st_uop(i)
        }

        //发射st_queue状态至ld_queue
        for (i <- 0 until p.STISSUE_DEPTH){
            io.st_queue_state(i) := !issue_queue(i).busy || 
                select_index(0).valid && select_index(0).bits === i.U ||
                select_index(1).valid && select_index(1).bits === i.U
        }
    }
}