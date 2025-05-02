package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class exu_issue_IO(implicit p: Parameters) extends Bundle {
    //来自Dispatch Unit的输入
    // val bits.iq_index = Input(Vec(p.DISPATCH_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //IQ ID,
    val dis_uop = Flipped(Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop())))  //来自Dispatch Unit的输入

    //with EXU
    val exu_issue_uop = Vec(p.CORE_WIDTH, Valid(new EXUISSUE_EXU_uop())) //发往EXU的uop
    val mul_ready = Input(Vec(p.MUL_NUM, Bool())) //乘法器的ready信号
    val div_ready = Input(Vec(p.DIV_NUM, Bool())) //除法器的ready信号

    //val dst_FU = Output(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.ALU_NUM).W)))  //发射的指令的目标功能单元
    //val issue_uop = Valid(Vec(p.CORE_WIDTH, new EXUISSUE_EXU_uop()))  //发射的指令(包含操作数的值)
    // val value_o1 = Output(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //发射的指令的操作数1
    // val value_o2 = Output(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //发射的指令的操作数2

    //PRF
    val exu_issue_raddr1 = Output(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.PRF_DEPTH).W))) //PRF读地址1
    val exu_issue_raddr2 = Output(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.PRF_DEPTH).W))) //PRF读地址2
    val exu_issue_value1 = Input(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //操作数1
    val exu_issue_value2 = Input(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //操作数2    

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

class exu_issue_queue(implicit p: Parameters) extends Module {
    val io = IO(new exu_issue_IO())
    val issue_queue = RegInit(
        VecInit(Seq.fill(p.EXUISSUE_DEPTH) {
            0.U.asTypeOf(new exu_issue_content())
        })
    )
    val payload = RegInit(
        VecInit(List.fill(p.EXUISSUE_DEPTH)(
            0.U.asTypeOf(new DISPATCH_EXUISSUE_uop())))) //发射队列的有效载荷

    //来自Dispatch的入队指令
    when(io.dis_uop(0).valid && !io.dis_uop(1).valid){
        payload(io.dis_uop(0).bits.iq_index) := io.dis_uop(0).bits
        issue_queue(io.dis_uop(0).bits.iq_index).busy := true.B
        issue_queue(io.dis_uop(0).bits.iq_index).instr_type := io.dis_uop(0).bits.instr_type
        issue_queue(io.dis_uop(0).bits.iq_index).ps1 := io.dis_uop(0).bits.ps1
        issue_queue(io.dis_uop(0).bits.iq_index).ps2 := io.dis_uop(0).bits.ps2

        //入队时判断ps1和ps2的ready信号
        val conditions1 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
            io.prf_valid(io.dis_uop(0).bits.ps1) ||
            (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === io.dis_uop(0).bits.ps1) ||
            (io.wb_uop2(i).valid && io.wb_uop2(i).bits.pdst === io.dis_uop(0).bits.ps1)
        }
        val conditions2 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
            io.prf_valid(io.dis_uop(0).bits.ps2) ||
            (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === io.dis_uop(0).bits.ps2) ||
            (io.wb_uop2(i).valid && io.wb_uop2(i).bits.pdst === io.dis_uop(0).bits.ps2)
        }
        when (conditions1.reduce(_ || _)) {
            issue_queue(io.dis_uop(0).bits.iq_index).ready1 := true.B
        }
        when (conditions2.reduce(_ || _)) {
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
                io.prf_valid(io.dis_uop(k).bits.ps1) ||
                (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === io.dis_uop(k).bits.ps1) ||
                (io.wb_uop2(i).valid && io.wb_uop2(i).bits.pdst === io.dis_uop(k).bits.ps1)
            }
            val conditions2 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                io.prf_valid(io.dis_uop(k).bits.ps2) ||
                (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === io.dis_uop(k).bits.ps2) ||
                (io.wb_uop2(i).valid && io.wb_uop2(i).bits.pdst === io.dis_uop(k).bits.ps2)
            }
            when (conditions1.reduce(_ || _)) {
                issue_queue(io.dis_uop(k).bits.iq_index).ready1 := true.B
            }
            when (conditions2.reduce(_ || _)) {
                issue_queue(io.dis_uop(k).bits.iq_index).ready2 := true.B
            }
            //结束ready信号赋值
        }
    }.otherwise{
        //不入队
    }
}