package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class st_issue_IO(implicit p: Parameters) extends CustomBundle {
    //来自Dispatch Unit的输入
    //val iq_id = Input(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //IQ ID
    val st_issue_uop = Flipped(Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_STISSUE_uop()))))  //来自Dispatch Unit的输入

    //发射到st的输出
    val store_uop = Valid(new STISSUE_STPIPE_uop())  //发射的指令
    // val value_o1 = Output(UInt(p.XLEN.W)) //发射的指令的操作数1
    // val value_o2 = Output(UInt(p.XLEN.W)) //发射的指令的操作数2

    //PRF
    val prf_raddr1 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址1
    val prf_raddr2 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址2
    val ps1_value = Input(UInt(p.XLEN.W)) //操作数1
    val ps2_value = Input(UInt(p.XLEN.W)) //操作数2

    //监听PRF的valid信号用于更新ready状态
    val prf_valid = Input(Vec(p.PRF_DEPTH, Bool())) //PRF的valid信号
//    //监听FU后级间寄存器内的物理寄存器ready信号
//    val wb_uop2 = Input(Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Valid(new ALU_WB_uop())))  //来自alu、mul、div、load pipeline的uop
    //val LDU_complete_uop2 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop
    //监听FU处物理寄存器的ready信号
    val wb_uop1 = Input(Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Valid(new ALU_WB_uop())))  //来自alu、mul、div、load pipeline的uop
    val bu_wb_uop = Input((Vec(p.BU_NUM, Valid(new BU_WB_uop()))))
    //val LDU_complete_uop1 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop

    //输出至Dispatch Unit以及ld_issue的信号
    val st_issued_index = Output(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W))) //更新IQ Freelist

    //with ROB
    val rob_commitsignal = Input(Vec(p.CORE_WIDTH, Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷

    //To ld_issue_queue
    val st_queue_state = Output(Vec(p.STISSUE_DEPTH, Bool()))//在ld_issue中要进一步处理

    //测试用
    val queue = Output(Vec(p.STISSUE_DEPTH, new st_issue_content()))
}

class st_iq_select_logic(implicit p: Parameters) extends CustomModule{
    val io = IO(new Bundle {
        val issue_queue = Input(Vec(p.STISSUE_DEPTH, new st_issue_content()))
        val sel_index = Output(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W))) //选择的索引
    })
    val readySt = VecInit((0 until p.STISSUE_DEPTH).map { i =>
        val q = io.issue_queue(i)
        q.busy && q.ready1 && q.ready2
    })
    val stOH1 = PriorityEncoderOH(readySt)
    val stIdx1 = OHToUInt(stOH1)
    val stV1 = readySt.asUInt.orR


    when(stV1) {
        io.sel_index.valid := true.B
        io.sel_index.bits := stIdx1
    }.otherwise {
        io.sel_index.valid := false.B
        io.sel_index.bits := 0.U
    }

}

//exu_issue->st的级间寄存器
class issue2st(implicit p: Parameters) extends CustomModule {
    val io = IO(new Bundle {
        val if_valid = Input(Bool()) //指令是否有效
        val ps1_value = Input(UInt(p.XLEN.W)) //操作数1
        val ps2_value = Input(UInt(p.XLEN.W)) //操作数2
        val dis_issue_uop = Input(new DISPATCH_STISSUE_uop()) //被select的uop
        val store_uop = Output(Valid(new STISSUE_STPIPE_uop())) //发往STPIPE的uop
    })
    val uop = RegInit(Valid(new STISSUE_STPIPE_uop()), 0.U.asTypeOf(Valid(new STISSUE_STPIPE_uop())))
    uop.valid := io.if_valid
    uop.bits.instr := io.dis_issue_uop.instr
    uop.bits.ps1_value := io.ps1_value
    uop.bits.ps2_value := io.ps2_value
    uop.bits.stq_index := io.dis_issue_uop.stq_index
    uop.bits.rob_index := io.dis_issue_uop.rob_index
    io.store_uop.valid := uop.valid
    io.store_uop.bits := uop.bits
}


class st_issue_content(implicit p: Parameters) extends Bundle {
    val busy = Bool() //busy bit
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W) //物理寄存器地址1
    val ready1 = Bool() //物理寄存器1的ready信号
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W) //物理寄存器地址2
    val ready2 = Bool() //物理寄存器2的ready信号
}

class st_issue_queue(implicit p: Parameters) extends CustomModule {
    val io = IO(new st_issue_IO())

    val VALID = io.st_issue_uop.valid
    val dis_uop = io.st_issue_uop.bits

    //存储结构定义
    val issue_queue = RegInit(
        VecInit(Seq.fill(p.STISSUE_DEPTH) {
            0.U.asTypeOf(new st_issue_content())
        })
    )

    //调试用代码
    io.queue := issue_queue
    printf(p"------ Issue Queue Contents ------\n\n")
    for (i <- 0 until p.EXUISSUE_DEPTH) {
        val entry = issue_queue(i)
        printf(p"Entry ${"%02d".format(i)}: " +
          p"Busy=${entry.busy} " +
          p"PS1=0x${Hexadecimal(entry.ps1)}[${entry.ready1}] " +
          p"PS2=0x${Hexadecimal(entry.ps2)}[${entry.ready2}]\n")
    }
    printf(p"-----------------------------------\n\n")

    val payload = RegInit(
        VecInit(Seq.fill(p.STISSUE_DEPTH) {
            0.U.asTypeOf(new DISPATCH_STISSUE_uop())
        })
    )
    //存储结构定义结束

    //初始化
    io.store_uop.bits := 0.U.asTypeOf(new STISSUE_STPIPE_uop())
    io.store_uop.valid := 0.B
    io.st_issued_index := 0.U.asTypeOf(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))
    io.prf_raddr1 := 0.U.asTypeOf(UInt(log2Ceil(p.PRF_DEPTH).W))
    io.prf_raddr2 := 0.U.asTypeOf(UInt(log2Ceil(p.PRF_DEPTH).W))
    io.st_queue_state := 0.U.asTypeOf(Vec(p.STISSUE_DEPTH, Bool()))
    //判断是否flush
    val flush = Wire(Bool())
    flush := io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
    when(flush){
        for (i <- 0 until p.STISSUE_DEPTH){
            issue_queue(i).busy := false.B
            issue_queue(i).ready1 := false.B
            issue_queue(i).ready2 := false.B
        }
    }.otherwise{
        //Dispatch入队命令
        when(VALID && dis_uop(0).valid && !dis_uop(1).valid){
            payload(dis_uop(0).bits.iq_index) := dis_uop(0).bits
            issue_queue(dis_uop(0).bits.iq_index).busy := true.B
            issue_queue(dis_uop(0).bits.iq_index).ps1 := dis_uop(0).bits.ps1
            issue_queue(dis_uop(0).bits.iq_index).ps2 := dis_uop(0).bits.ps2

            //入队时判断ps1和ps2的ready信号
            //alu写回
            val alu_conditions1 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === dis_uop(0).bits.ps1)
            }
            val alu_conditions2 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                io.prf_valid(dis_uop(0).bits.ps2) ||
                (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === dis_uop(0).bits.ps2)
            }
            //bu写回
            val bu_conditions1 = for (i <- 0 until p.BU_NUM ) yield {
                (io.bu_wb_uop(i).valid && !io.bu_wb_uop(i).bits.is_conditional && io.bu_wb_uop(i).bits.pdst === dis_uop(0).bits.ps1)
            }
            val bu_conditions2 = for (i <- 0 until p.BU_NUM) yield {
                (io.bu_wb_uop(i).valid && !io.bu_wb_uop(i).bits.is_conditional && io.bu_wb_uop(i).bits.pdst === dis_uop(0).bits.ps2)
            }
            when (alu_conditions1.reduce(_ || _) || bu_conditions1.reduce(_ || _) || io.prf_valid(dis_uop(0).bits.ps1)) {
                issue_queue(dis_uop(0).bits.iq_index).ready1 := true.B
            } .otherwise{
                issue_queue(dis_uop(0).bits.iq_index).ready1 := false.B
            }
            when (alu_conditions2.reduce(_ || _) || bu_conditions2.reduce(_ || _) || io.prf_valid(dis_uop(0).bits.ps2)) {
                issue_queue(dis_uop(0).bits.iq_index).ready2 := true.B
            } .otherwise{
                issue_queue(dis_uop(0).bits.iq_index).ready2 := false.B
            }
            //结束ready信号赋值
        } .elsewhen(VALID && dis_uop(0).valid && dis_uop(1).valid){
            for (k <- 0 until 2){
                payload(dis_uop(k).bits.iq_index) := dis_uop(k).bits
                issue_queue(dis_uop(k).bits.iq_index).busy := true.B
                issue_queue(dis_uop(k).bits.iq_index).ps1 := dis_uop(k).bits.ps1
                issue_queue(dis_uop(k).bits.iq_index).ps2 := dis_uop(k).bits.ps2

                //入队时判断ps1和ps2的ready信号
                val alu_conditions1 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                    (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === dis_uop(k).bits.ps1)
                }
                val alu_conditions2 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                    (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === dis_uop(k).bits.ps2)
                }
                val bu_conditions1 = for (i <- 0 until p.BU_NUM ) yield {
                    (io.bu_wb_uop(i).valid && !io.bu_wb_uop(i).bits.is_conditional && io.bu_wb_uop(i).bits.pdst === dis_uop(0).bits.ps1)
                }
                val bu_conditions2 = for (i <- 0 until p.BU_NUM) yield {
                    (io.bu_wb_uop(i).valid && !io.bu_wb_uop(i).bits.is_conditional && io.bu_wb_uop(i).bits.pdst === dis_uop(0).bits.ps2)
                }
                when (alu_conditions1.reduce(_ || _) || bu_conditions1.reduce(_ || _) || io.prf_valid(dis_uop(k).bits.ps1)) {
                    issue_queue(dis_uop(k).bits.iq_index).ready1 := true.B
                } .otherwise{
                    issue_queue(dis_uop(k).bits.iq_index).ready1 := false.B
                }
                when (alu_conditions2.reduce(_ || _) || bu_conditions2.reduce(_ || _) || io.prf_valid(dis_uop(k).bits.ps2)) {
                    issue_queue(dis_uop(k).bits.iq_index).ready2 := true.B
                } .otherwise{
                    issue_queue(dis_uop(0).bits.iq_index).ready2 := false.B
                }
                //结束ready信号赋值
            }
        }.otherwise{
            //不入队
        }

        //每周期监听后续寄存器的ready信号，更新ready状态
        for (i <- 0 until p.STISSUE_DEPTH){
            when (issue_queue(i).busy){
                val alu_update_conditions1 = for (j <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                    (io.wb_uop1(j).valid && io.wb_uop1(j).bits.pdst === issue_queue(i).ps1)
                }
                val alu_update_conditions2 = for (j <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                    (io.wb_uop1(j).valid && io.wb_uop1(j).bits.pdst === issue_queue(i).ps2)
                }
                val bu_update_conditions1 = for (j <- 0 until p.BU_NUM ) yield {
                    (io.bu_wb_uop(j).valid && !io.bu_wb_uop(j).bits.is_conditional && io.bu_wb_uop(j).bits.pdst === issue_queue(i).ps1)
                }
                val bu_update_conditions2 = for (j <- 0 until p.BU_NUM) yield {
                    (io.bu_wb_uop(j).valid && !io.bu_wb_uop(j).bits.is_conditional && io.bu_wb_uop(j).bits.pdst === issue_queue(i).ps2)
                }
                when (alu_update_conditions1.reduce(_ || _) || bu_update_conditions1.reduce(_ || _) || io.prf_valid(issue_queue(i).ps1)) {
                    issue_queue(i).ready1 := true.B
                }
                when (alu_update_conditions2.reduce(_ || _) || bu_update_conditions2.reduce(_ || _) || io.prf_valid(issue_queue(i).ps2)) {
                    issue_queue(i).ready2 := true.B
                }
            }
        }


    }
    //Select Logic
    val select_logic = Module(new st_iq_select_logic())
    val select_index = Wire(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))
    select_logic.io.issue_queue := issue_queue
    select_index := select_logic.io.sel_index

    //读PRF
    when (select_index.valid){
        io.prf_raddr1 := issue_queue(select_index.bits).ps1
        io.prf_raddr2 := issue_queue(select_index.bits).ps2
    }.otherwise{
        io.prf_raddr1 := 0.U
        io.prf_raddr2 := 0.U
    }


    //更新IQ Freelist的信号
    when (!flush && select_index.valid){
        io.st_issued_index.valid := true.B
        io.st_issued_index.bits := select_index.bits
    }.otherwise{
        io.st_issued_index.valid := false.B
        io.st_issued_index.bits := 0.U
    }

    //发射命令到级间寄存器
    val issue_to_st = Module(new issue2st())
    //在其他issue_queue中，有flush信号接入级间寄存器用于清空，但我发现这是没有必要的，因为下方的判断逻辑已经可以在flush时让if_valid置0
    when (!flush && select_index.valid){
        issue_to_st.io.if_valid := true.B
        issue_queue(select_index.bits).busy := false.B
        issue_queue(select_index.bits).ready1 := false.B
        issue_queue(select_index.bits).ready2 := false.B
    }.otherwise{
        issue_to_st.io.if_valid := false.B
    }
    issue_to_st.io.dis_issue_uop := payload(select_index.bits)
    issue_to_st.io.ps1_value := io.ps1_value
    issue_to_st.io.ps2_value := io.ps2_value
    io.store_uop.bits := issue_to_st.io.store_uop.bits
    io.store_uop.valid := issue_to_st.io.store_uop.valid

    //发射st_queue状态至ld_queue
    for (i <- 0 until p.STISSUE_DEPTH){
        io.st_queue_state(i) := !issue_queue(i).busy
    }
}
