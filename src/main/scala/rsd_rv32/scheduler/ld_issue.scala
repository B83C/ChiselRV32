package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class ld_issue_IO(implicit p: Parameters) extends CustomBundle {
    //来自Dispatch Unit的输入
    val ld_issue_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new DISPATCH_LDISSUE_uop())))  //来自Dispatch Unit的输入

    //发射到ld的输出
    val load_uop = Decoupled(new LDISSUE_LDPIPE_uop())  //发射的指令
    // val value_o1 = Output(UInt(p.XLEN.W)) //发射的指令的操作数1
    // val value_o2 = Output(UInt(p.XLEN.W)) //发射的指令的操作数2

    //PRF
    val prf_raddr = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址1
    //val raddr2 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址2
    val prf_value = Input(UInt(p.XLEN.W)) //操作数1
    //val value_i2 = Input(UInt(p.XLEN.W)) //操作数2    

    //监听PRF的valid信号用于更新ready状态
    val prf_valid = Input(Vec(p.PRF_DEPTH, Bool())) //PRF的valid信号
//    //监听FU后级间寄存器内的物理寄存器ready信号
//    val wb_uop2 = Input((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Valid(new ALU_WB_uop()))))  //来自alu、mul、div、load pipeline的uop
    //val ldu_wb_uop2 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop
    //监听FU处物理寄存器的ready信号
    val wb_uop1 = Input((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Valid(new ALU_WB_uop())))) //来自alu、mul、div、load pipeline的uop
    //val ldu_wb_uop1 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop

    //输出至Dispatch Unit的信号
    val ld_issued_index = Output(Valid(UInt(log2Ceil(p.LDISSUE_DEPTH).W))) //更新IQ Freelist

    //with ROB
    val rob_commitsignal = Input(Vec(p.CORE_WIDTH, Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷
    //st_issue的busy信息以及该周期发射的store指令号
    val st_issue_unbusy = Input(Vec(p.STISSUE_DEPTH, Bool()))
    val st_issued_index = Flipped(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))

    //测试接口
    val queue = Output(Vec(p.LDISSUE_DEPTH, new ld_issue_content()))
}

class ld_iq_select_logic(implicit p: Parameters) extends CustomModule{
    val io = IO(new Bundle {
        val issue_queue = Input(Vec(p.LDISSUE_DEPTH, new ld_issue_content()))
        val sel_index = Output(Valid(UInt(log2Ceil(p.LDISSUE_DEPTH).W))) //选择的索引
        val ldpipe_ready = Input(Bool())
    })
    val readyLd = VecInit((0 until p.STISSUE_DEPTH).map { i =>
        val q = io.issue_queue(i)
        q.busy && q.ps_ready && q.st_ready.reduce(_ || _)
    })
    val ldOH1 = PriorityEncoderOH(readyLd)
    val ldIdx1 = OHToUInt(ldOH1)
    val ldV1 = readyLd.asUInt.orR


    when(ldV1 && io.ldpipe_ready) {
        io.sel_index.valid := true.B
        io.sel_index.bits := ldIdx1
    }.otherwise {
        io.sel_index.valid := false.B
        io.sel_index.bits := 0.U
    }

}

class issue2ld(implicit p: Parameters) extends CustomModule {
    val io = IO(new Bundle {
        val if_valid = Input(Bool()) //指令是否有效
        val ps_value = Input(UInt(p.XLEN.W)) //操作数1
        val dis_issue_uop = Input(new DISPATCH_LDISSUE_uop()) //被select的uop
        val load_uop = Output(Valid((new LDISSUE_LDPIPE_uop()))) //发往STPIPE的uop
    })
    val uop = Reg(Valid(new LDISSUE_LDPIPE_uop()))
    uop.valid := io.if_valid
    uop.bits.instr := io.dis_issue_uop.instr
    uop.bits.pdst := io.dis_issue_uop.pdst
    uop.bits.ps1_value := io.ps_value
    uop.bits.stq_tail := io.dis_issue_uop.stq_tail
    uop.bits.rob_index := io.dis_issue_uop.rob_index
    io.load_uop.valid := uop.valid
    io.load_uop.bits := uop.bits
}

class ld_issue_content(implicit p: Parameters) extends Bundle {
    val busy = Bool() //busy flag
    val ps = UInt(log2Ceil(p.PRF_DEPTH).W) //操作数的物理寄存器地址
    val ps_ready = Bool() //操作数的ready信号
    val st_ready = Vec(p.STISSUE_DEPTH, Bool())
}

class ld_issue_queue(implicit p: Parameters) extends CustomModule {
    val io = IO(new ld_issue_IO())
    val st_queue_state = Wire(Vec(p.STISSUE_DEPTH, Bool()))
    //用于生成ready矩阵
    for (i <- 0 until p.STISSUE_DEPTH){
        st_queue_state(i) := io.st_issue_unbusy(i) || io.st_issued_index.valid && io.st_issued_index.bits === i.U
    }

    val issue_queue = RegInit(
        VecInit(Seq.fill(p.LDISSUE_DEPTH) {
            0.U.asTypeOf(new ld_issue_content())
        })
    )
    //调试用代码
    io.queue := issue_queue
    printf(p"------ Issue Queue Contents ------\n\n")
    for (i <- 0 until p.EXUISSUE_DEPTH) {
        val entry = issue_queue(i)
        printf(p"Entry ${"%02d".format(i)}: " +
          p"Busy=${entry.busy} " +
          p"PS1=0x${Hexadecimal(entry.ps)}[${entry.ps_ready}] " +
          p"st_ready=[${entry.st_ready}]\n")
    }
    printf(p"-----------------------------------\n\n")

    val payload = RegInit(
        VecInit(Seq.fill(p.LDISSUE_DEPTH) {
            0.U.asTypeOf(new DISPATCH_LDISSUE_uop())
        })
    )
    //初始化
    io.load_uop.bits := 0.U.asTypeOf(new LDISSUE_LDPIPE_uop())
    io.load_uop.valid := 0.B
    io.ld_issued_index := 0.U.asTypeOf(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))
    io.prf_raddr := 0.U.asTypeOf(UInt(log2Ceil(p.PRF_DEPTH).W))
    //判断flush
    when(io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred){
        for (i <- 0 until p.LDISSUE_DEPTH){
            issue_queue(i).busy := false.B
            issue_queue(i).ps_ready := false.B
        }
    }.otherwise{
        //Dispatch入队命令
        when(io.ld_issue_uop
(0).valid && !io.ld_issue_uop
(1).valid){
            payload(io.ld_issue_uop
    (0).bits.iq_index) := io.ld_issue_uop
(0).bits
            issue_queue(io.ld_issue_uop
    (0).bits.iq_index).busy := true.B
            issue_queue(io.ld_issue_uop
    (0).bits.iq_index).ps := io.ld_issue_uop
(0).bits.ps1

            //入队时判断ps1和ps2的ready信号
            val conditions1 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === io.ld_issue_uop
    (0).bits.ps1)
            }
            when (conditions1.reduce(_ || _) || io.prf_valid(io.ld_issue_uop
    (0).bits.ps1)) {
                issue_queue(io.ld_issue_uop
        (0).bits.iq_index).ps_ready := true.B
            }.otherwise{
                issue_queue(io.ld_issue_uop
        (0).bits.iq_index).ps_ready := false.B
            }
            for (i <- 0 until p.STISSUE_DEPTH){
                issue_queue(io.ld_issue_uop
        (0).bits.iq_index).st_ready(i) := st_queue_state(i)
            }
            //结束ready信号赋值
        } .elsewhen(io.ld_issue_uop
(0).valid && io.ld_issue_uop
(1).valid){
            for (k <- 0 until 2){
                payload(io.ld_issue_uop
        (k).bits.iq_index) := io.ld_issue_uop
    (k).bits
                issue_queue(io.ld_issue_uop
        (k).bits.iq_index).busy := true.B
                issue_queue(io.ld_issue_uop
        (k).bits.iq_index).ps := io.ld_issue_uop
    (k).bits.ps1

                //入队时判断ps1和ps2的ready信号
                val conditions1 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                    (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === io.ld_issue_uop
        (k).bits.ps1)
                }
                when (conditions1.reduce(_ || _) || io.prf_valid(io.ld_issue_uop
        (k).bits.ps1)) {
                    issue_queue(io.ld_issue_uop
            (k).bits.iq_index).ps_ready := true.B
                } .otherwise{
                    issue_queue(io.ld_issue_uop
            (k).bits.iq_index).ps_ready := false.B
                }
                for (i <- 0 until p.STISSUE_DEPTH){
                    issue_queue(io.ld_issue_uop
            (k).bits.iq_index).st_ready(i) := st_queue_state(i)
                }
                //结束ready信号赋值
            }
        } .otherwise{
            //不入队
        }

        //每周期监听后续寄存器的ready信号，更新ps_ready状态
        for (i <- 0 until p.LDISSUE_DEPTH){
            when (issue_queue(i).busy){
                val update_conditions1 = for (j <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                    (io.wb_uop1(j).valid && io.wb_uop1(j).bits.pdst === issue_queue(i).ps)
                }
                when (update_conditions1.reduce(_ || _) || io.prf_valid(issue_queue(i).ps)) {
                    issue_queue(i).ps_ready := true.B
                }
            }
        }

        //每周期监听st_issue发射的指令，更新st_ready状态
        for (i <- 0 until p.LDISSUE_DEPTH){
            when (issue_queue(i).busy && io.st_issued_index.valid){
                issue_queue(i).st_ready(io.st_issued_index.bits) := true.B
            }
        }

        //Select Logic
        val select_logic = Module(new ld_iq_select_logic())
        val select_index = Wire(Valid(UInt(log2Ceil(p.STISSUE_DEPTH).W)))
        select_logic.io.issue_queue := issue_queue
        select_logic.io.ldpipe_ready := io.load_uop.ready
        select_index := select_logic.io.sel_index

        //读PRF
        when (select_index.valid){
            io.prf_raddr := issue_queue(select_index.bits).ps
        }.otherwise{
            io.prf_raddr := 0.U
        }

        //更新IQ Freelist的信号
        when (select_index.valid){
            io.ld_issued_index.valid := true.B
            io.ld_issued_index.bits := select_index.bits
        }.otherwise{
            io.ld_issued_index.valid := false.B
            io.ld_issued_index.bits := 0.U
        }

        //发射命令到级间寄存器
        val issue_to_ld = Module(new issue2ld())

        when (select_index.valid){
            issue_to_ld.io.if_valid := true.B
            issue_queue(select_index.bits).busy := false.B
            issue_queue(select_index.bits).ps_ready := false.B
            issue_queue(select_index.bits).st_ready := 0.U.asTypeOf(Vec(p.STISSUE_DEPTH, Bool()))
        }.otherwise{
            issue_to_ld.io.if_valid := false.B
        }
        issue_to_ld.io.dis_issue_uop := payload(select_index.bits)
        issue_to_ld.io.ps_value := io.prf_value
        io.load_uop.bits := issue_to_ld.io.load_uop.bits
        io.load_uop.valid := issue_to_ld.io.load_uop.valid
    }
}
