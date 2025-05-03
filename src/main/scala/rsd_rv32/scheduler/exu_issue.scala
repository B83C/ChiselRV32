package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class exu_issue_IO(implicit p: Parameters) extends Bundle {
    //来自Dispatch Unit的输入
    // val bits.iq_index = Input(Vec(p.DISPATCH_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //IQ ID,
    val dis_uop = Flipped(Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop())))  //来自Dispatch Unit的输入

    //with EXU
    val issue_exu_uop = Vec(p.CORE_WIDTH, Valid(new EXUISSUE_EXU_uop())) //发往EXU的uop
    val mul_ready = Input(Vec(p.MUL_NUM, Bool())) //乘法器的ready信号
    val div_ready = Input(Vec(p.DIV_NUM, Bool())) //除法器的ready信号

    //val dst_FU = Output(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.ALU_NUM).W)))  //发射的指令的目标功能单元
    //val issue_uop = Valid(Vec(p.CORE_WIDTH, new EXUISSUE_EXU_uop()))  //发射的指令(包含操作数的值)
    // val value_o1 = Output(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //发射的指令的操作数1
    // val value_o2 = Output(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //发射的指令的操作数2

    //PRF
    val prf_raddr1 = Output(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.PRF_DEPTH).W))) //PRF读地址1
    val prf_raddr2 = Output(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.PRF_DEPTH).W))) //PRF读地址2
    val ps1_value = Input(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //操作数1
    val ps2_value = Input(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //操作数2    

    //监听PRF的valid信号用于更新ready状态
    val prf_valid = Input(Vec(p.PRF_DEPTH, Bool())) //PRF的valid信号
    //监听FU后级间寄存器内的物理寄存器ready信号
    val wb_uop2 = Flipped((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Valid(new ALU_WB_uop()))))  //来自alu、mul、div、load pipeline的uop
    //监听FU处物理寄存器的ready信号
    val wb_uop1 = Flipped((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Valid(new ALU_WB_uop())))) //来自alu、mul、div、load pipeline的uop
    //val ldu_wb_uop1 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop

    //输出至Dispatch Unit的信号
    val exu_issued_index = Output(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.EXUISSUE_DEPTH).W))) //更新IQ Freelist

    //with ROB
    val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷
}

class exu_issue_content(implicit p: Parameters) extends Bundle {
    val busy = Bool() //busy flag
    val instr_type = InstrType() //指令类型
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W) //操作数1的物理寄存器地址
    val ready1 = Bool() //操作数1的ready信号
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W) //操作数2的物理寄存器地址
    val ready2 = Bool() //操作数2的ready信号
}

//需要完善！如何避免同时选到两条乘法或两条除法
class select_logic(implicit p: Parameters) extends Module {
    val io = IO(new Bundle {
        val mul_ready = Input(Vec(p.MUL_NUM, Bool())) //乘法器的ready信号
        val div_ready = Input(Vec(p.DIV_NUM, Bool())) //除法器的ready信号
        val issue_queue = Input(Vec(p.EXUISSUE_DEPTH, new exu_issue_content()))
        val sel_index = Output(Vec(2, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W)))) //选择的指令的索引
    })

    val tuples = (0 until p.EXUISSUE_DEPTH).map { i =>
        (io.issue_queue(i).busy && io.issue_queue(i).ready1 && io.issue_queue(i).ready2 && (
        io.issue_queue(i).instr_type === InstrType.ALU ||
        (io.issue_queue(i).instr_type === InstrType.MUL && )), i.U)
    }
    val validTuples = tuples.filter(_._1)
    val paddedTuples = VecInit(
        (validTuples ++ Seq(
            (false.B, 0.U),
            (false.B, 0.U)
        )).take(2)
    )
    for (i <- 0 until 2) {
        io.sel_index(i).valid := paddedTuples(i)._1
        io.sel_index(i).bits := paddedTuples(i)._2
    }
}

//exu_issue->exu的级间寄存器
class issue2exu(implicit p: Parameters) extends Module {
    val io = IO(new Bundle {
        val if_valid =Input(Vec(p.CORE_WIDTH, Bool())) //指令是否有效
        val ps1_value = Input(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //操作数1
        val ps2_value = Input(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //操作数2
        val dis_issue_uop = Input(Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop()))) //来自Dispatch的uop
        val issue_exu_uop = Output(Vec(p.CORE_WIDTH, Valid(new EXUISSUE_EXU_uop()))) //发往EXU的uop
    })
    val uop = Reg(Vec(p.CORE_WIDTH, Valid(new EXUISSUE_EXU_uop())))
    for (i <-0 until p.CORE_WIDTH){
        uop(i).valid := io.dis_issue_uop(i).valid && io.if_valid(i)
        uop(i).bits.instr := io.dis_issue_uop(i).bits.instr
        uop(i).bits.instr_type := io.dis_issue_uop(i).bits.instr_type
        uop(i).bits.fu_signals := io.dis_issue_uop(i).bits.fu_signals
        uop(i).bits.ps1_value := io.ps1_value(i)
        uop(i).bits.ps2_value := io.ps2_value(i)
        uop(i).bits.pdst := io.dis_issue_uop(i).bits.pdst
        uop(i).bits.branch_pred := io.dis_issue_uop(i).bits.branch_pred
        uop(i).bits.target_PC := io.dis_issue_uop(i).bits.target_PC
        uop(i).bits.rob_index := io.dis_issue_uop(i).bits.rob_index
    }
    io.issue_exu_uop := uop
}

class exu_issue_queue(implicit p: Parameters) extends Module {
    val io = IO(new exu_issue_IO())

    val issue_queue = RegInit(
        VecInit(Seq.fill(p.EXUISSUE_DEPTH) {
            0.U.asTypeOf(new exu_issue_content())
        })
    )
    /*
    val payload = RegInit(
        VecInit(List.fill(p.EXUISSUE_DEPTH)(
            0.U.asTypeOf(new DISPATCH_EXUISSUE_uop())))) //发射队列的有效载荷
    */
    val payload = RegInit(
        VecInit(Seq.fill(p.EXUISSUE_DEPTH) {
            0.U.asTypeOf(new DISPATCH_EXUISSUE_uop())
        })
    )

    val select_index = Vec(2, Wire(Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W))))

    //来自Dispatch的入队指令
    when(io.dis_uop(0).valid && !io.dis_uop(1).valid){
        payload(io.dis_uop(0).bits.iq_index) := io.dis_uop(0).bits
        issue_queue(io.dis_uop(0).bits.iq_index).busy := true.B
        issue_queue(io.dis_uop(0).bits.iq_index).instr_type := io.dis_uop(0).bits.instr_type
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
        when (conditions1.reduce(_ || _) || io.prf_valid(io.dis_uop(0).bits.ps1) || (io.dis_uop(k).bits.fu_signals.opr1_sel =/= OprSel.REG)) {
            issue_queue(io.dis_uop(0).bits.iq_index).ready1 := true.B
        }
        when (conditions2.reduce(_ || _) || io.prf_valid(io.dis_uop(0).bits.ps1) || (io.dis_uop(k).bits.fu_signals.opr2_sel =/= OprSel.REG)) {
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
    for (i <- 0 until p.EXUISSUE_DEPTH){
        when (issue_queue(i).busy){
            val update_conditions1 = for (j <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                io.prf_valid(io.dis_uop(0).bits.ps1) ||
                (io.wb_uop1(j).valid && io.wb_uop1(j).bits.pdst === io.dis_uop(i).bits.ps1) ||
                (io.wb_uop2(j).valid && io.wb_uop2(j).bits.pdst === io.dis_uop(i).bits.ps1)
            }
            val update_conditions2 = for (j <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                io.prf_valid(io.dis_uop(0).bits.ps2) ||
                (io.wb_uop1(j).valid && io.wb_uop1(j).bits.pdst === io.dis_uop(i).bits.ps2) ||
                (io.wb_uop2(j).valid && io.wb_uop2(j).bits.pdst === io.dis_uop(i).bits.ps2)
            }
            when (update_conditions1.reduce(_ || _) || io.prf_valid(io.dis_uop(i).bits.ps1)) {
                issue_queue(i).ready1 := true.B
            }
            when (update_conditions2.reduce(_ || _) || io.prf_valid(io.dis_uop(i).bits.ps2)) {
                issue_queue(i).ready2 := true.B
            }
        }
    }

    //Select logicPRF
    val select = Module(new select_logic())
    select.io.issue_queue := issue_queue
    select_index := select.io.sel_index
    //读PRF
    for (i <- 0 until 2){
        when (select_index(i).valid){
            io.prf_raddr1(i) := issue_queue(select_index(i).bits).ps1
            io.prf_raddr2(i) := issue_queue(select_index(i).bits).ps2
        }
    }

    //发射命令到级间寄存器
    val issue_to_exu = Module(new issue2exu())
    for (i <- 0 until 2){
        when (select_index(i).valid){
            issue_to_exu.io.if_valid(i) := true.B
        }.otherwise{
            issue_to_exu.io.if_valid(i) := false.B
        }
        issue_to_exu.io.dis_issue_uop(i) := payload(select_index(i).bits)
        issue_to_exu.io.ps1_value(i) := io.ps1_value(i)
        issue_to_exu.io.ps2_value(i) := io.ps2_value(i)
        io.issue_exu_uop(i) := issue_to_exu.io.issue_exu_uop(i)

        issue_queue(select_index(i).bits).busy := false.B
        issue_queue(select_index(i).bits).ready1 := false.B
        issue_queue(select_index(i).bits).ready2 := false.B
    }

    //更新IQ Freelist
}