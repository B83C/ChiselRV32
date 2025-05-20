package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

class exu_issue_IO(implicit p: Parameters) extends CustomBundle {
    //来自Dispatch Unit的输入
    // val bits.iq_index = Input(Vec(p.DISPATCH_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //IQ ID,
    val exu_issue_uop = Flipped(Valid(Vec(p.CORE_WIDTH, Valid(new DISPATCH_EXUISSUE_uop()))))  //来自Dispatch Unit的输入

    //with EXU
    val execute_uop = Vec(p.CORE_WIDTH, Valid(new EXUISSUE_EXU_uop())) //发往EXU的uop
    val mul_ready = Input(Bool()) //乘法器的ready信号
    val div_ready = Input(Bool()) //除法器的ready信号

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
//    //监听FU后级间寄存器内的物理寄存器ready信号
//    val wb_uop2 = Input((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Valid(new ALU_WB_uop()))))  //来自alu、mul、div、load pipeline的uop
    //PRF前的ready信号
    val wb_uop1 = Input((Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Valid(new ALU_WB_uop())))) //来自alu、mul、div、load pipeline的uop
    val bu_wb_uop = Input((Vec(p.BU_NUM, Valid(new BU_WB_uop()))))
    //val ldu_wb_uop1 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop

    //输出至Dispatch Unit的信号
    val exu_issued_index = Output(Vec(p.CORE_WIDTH, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W)))) //更新IQ Freelist

    //with ROB
    val rob_commitsignal = Input(Vec(p.CORE_WIDTH, Valid(new ROBContent()))) //ROB提交时的广播信号，发生误预测时对本模块进行冲刷

    //以下是测试时需要的输出
    val queue = Output(Vec(p.EXUISSUE_DEPTH, new exu_issue_content())) //发射队列的内容
}

class exu_issue_content(implicit p: Parameters) extends Bundle {
    val busy = Bool() //busy flag
    val instr_type = InstrType() //指令类型
    val ps1 = UInt(log2Ceil(p.PRF_DEPTH).W) //操作数1的物理寄存器地址
    val ready1 = Bool() //操作数1的ready信号
    val ps2 = UInt(log2Ceil(p.PRF_DEPTH).W) //操作数2的物理寄存器地址
    val ready2 = Bool() //操作数2的ready信号
}

class exu_iq_select_logic(implicit p: Parameters) extends CustomModule {
    val io = IO(new Bundle {
        val mul_ready = Input(Bool()) //乘法器的ready信号
        val div_ready = Input(Bool()) //除法器的ready信号
        val issue_queue = Input(Vec(p.EXUISSUE_DEPTH, new exu_issue_content()))
        val sel_index = Output(Vec(2, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W)))) //选择的指令的索引
    })
    //生成ready序列
    val readyMul = VecInit((0 until p.EXUISSUE_DEPTH).map { i =>
        val q = io.issue_queue(i)
        q.busy && q.ready1 && q.ready2 && q.instr_type === InstrType.MUL && io.mul_ready
    })
    val readyDiv = VecInit((0 until p.EXUISSUE_DEPTH).map { i =>
        val q = io.issue_queue(i)
        q.busy && q.ready1 && q.ready2 && q.instr_type === InstrType.DIV_REM && io.div_ready
    })
    val readyAlu = VecInit((0 until p.EXUISSUE_DEPTH).map { i =>
        val q = io.issue_queue(i)
        q.busy && q.ready1 && q.ready2 && !(q.instr_type === InstrType.MUL || q.instr_type === InstrType.DIV_REM)
    })
    //选择乘除法就绪命令
    val mulOH = PriorityEncoderOH(readyMul)
    val mulIdx = OHToUInt(mulOH)
    val mulV = readyMul.asUInt.orR

    val divOH = PriorityEncoderOH(readyDiv)
    val divIdx = OHToUInt(divOH)
    val divV = readyDiv.asUInt.orR
    //选择alu就绪命令
    val aluOH1 = PriorityEncoderOH(readyAlu)
    val aluIdx1 = OHToUInt(aluOH1)
    val aluV1 = readyAlu.asUInt.orR

    val aluMasked2 = Wire(Vec(p.EXUISSUE_DEPTH, Bool()))
    for (i <- 0 until p.EXUISSUE_DEPTH) {
        aluMasked2(i) := readyAlu(i) && !aluOH1(i)
    }
    val aluOH2 = PriorityEncoderOH(aluMasked2)
    val aluIdx2 = OHToUInt(aluOH2)
    val aluV2 = aluMasked2.asUInt.orR
    //暴力枚举出应该发射的命令
    when (mulV) {
        when (divV){
            io.sel_index(0).valid := true.B
            io.sel_index(0).bits := mulIdx
            io.sel_index(1).valid := true.B
            io.sel_index(1).bits := divIdx
        }.otherwise{
            when (aluV1) {
                io.sel_index(0).valid := true.B
                io.sel_index(0).bits := mulIdx
                io.sel_index(1).valid := true.B
                io.sel_index(1).bits := aluIdx1
            }.otherwise{
                io.sel_index(0).valid := true.B
                io.sel_index(0).bits := mulIdx
                io.sel_index(1).valid := false.B
                io.sel_index(1).bits := 0.U
            }
        }
    }.otherwise{
        when (divV){
            when (aluV1) {
                io.sel_index(0).valid := true.B
                io.sel_index(0).bits := divIdx
                io.sel_index(1).valid := true.B
                io.sel_index(1).bits := aluIdx1
            }.otherwise{
                io.sel_index(0).valid := true.B
                io.sel_index(0).bits := divIdx
                io.sel_index(1).valid := false.B
                io.sel_index(1).bits := 0.U
            }
        }.otherwise{
            when (aluV1){
                when (aluV2) {
                    io.sel_index(0).valid := true.B
                    io.sel_index(0).bits := aluIdx1
                    io.sel_index(1).valid := true.B
                    io.sel_index(1).bits := aluIdx2
                }.otherwise{
                    io.sel_index(0).valid := true.B
                    io.sel_index(0).bits := aluIdx1
                    io.sel_index(1).valid := false.B
                    io.sel_index(1).bits := 0.U
                }
            }.otherwise{
                io.sel_index(0).valid := false.B
                io.sel_index(0).bits := 0.U
                io.sel_index(1).valid := false.B
                io.sel_index(1).bits := 0.U
            }
        }
    }
}

//exu_issue->exu的级间寄存器
class issue2exu(implicit p: Parameters) extends CustomModule {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val if_valid = Input(Vec(p.CORE_WIDTH, Bool())) //指令是否有效
        val ps1_value = Input(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //操作数1
        val ps2_value = Input(Vec(p.CORE_WIDTH, UInt(p.XLEN.W))) //操作数2
        val dis_issue_uop = Input(Vec(p.CORE_WIDTH, new DISPATCH_EXUISSUE_uop())) //来自Dispatch的uop
        val execute_uop = Output(Vec(p.CORE_WIDTH, Valid(new EXUISSUE_EXU_uop()))) //发往EXU的uop
    })
    val uop = RegInit(Vec(p.CORE_WIDTH, Valid(new EXUISSUE_EXU_uop())), 0.U.asTypeOf(Vec(p.CORE_WIDTH, Valid(new EXUISSUE_EXU_uop()))))
    for (i <-0 until p.CORE_WIDTH){
        uop(i).valid := io.if_valid(i) && io.flush
        uop(i).bits.instr := io.dis_issue_uop(i).instr
        uop(i).bits.instr_addr := io.dis_issue_uop(i).instr_addr
        uop(i).bits.instr_type := io.dis_issue_uop(i).instr_type
        uop(i).bits.fu_signals := io.dis_issue_uop(i).fu_signals
        uop(i).bits.ps1_value := io.ps1_value(i)
        uop(i).bits.ps2_value := io.ps2_value(i)
        uop(i).bits.pdst := io.dis_issue_uop(i).pdst
        uop(i).bits.branch_pred := io.dis_issue_uop(i).branch_pred
        uop(i).bits.target_PC := io.dis_issue_uop(i).target_PC
        uop(i).bits.rob_index := io.dis_issue_uop(i).rob_index
    }
    io.execute_uop := uop
}

class exu_issue_queue(implicit p: Parameters) extends CustomModule {
    val io = IO(new exu_issue_IO())

    val VALID = io.exu_issue_uop.valid
    val dis_uop = io.exu_issue_uop.bits

    val issue_queue = RegInit(
        VecInit(Seq.fill(p.EXUISSUE_DEPTH) {
            0.U.asTypeOf(new exu_issue_content())
        })
    )

    //调试用代码
    io.queue := issue_queue
    printf(p"------ Issue Queue Contents ------\n\n")
    for (i <- 0 until p.EXUISSUE_DEPTH) {
        val entry = issue_queue(i)
        printf(p"Entry ${"%02d".format(i)}: " +
          p"Busy=${entry.busy} " +
          p"Type=${entry.instr_type} " +
          p"PS1=0x${Hexadecimal(entry.ps1)}[${entry.ready1}] " +
          p"PS2=0x${Hexadecimal(entry.ps2)}[${entry.ready2}]\n")
    }
    printf(p"-----------------------------------\n\n")

    /*
    val payload = RegInit(
        VecInit(List.fill(p.EXUISSUE_DEPTH)(
            0.U.asTypeOf(new DISPATCH_EXUISSUE_uop())))) //发射队列的有效载荷
    */
    // Payload Table
    val payload = RegInit(
        VecInit(Seq.fill(p.EXUISSUE_DEPTH) {
            0.U.asTypeOf(new DISPATCH_EXUISSUE_uop())
        })
    )


    io.execute_uop := 0.U.asTypeOf(Vec(p.CORE_WIDTH, Valid(new EXUISSUE_EXU_uop())))
    io.exu_issued_index := 0.U.asTypeOf(Vec(p.CORE_WIDTH, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W))))
    io.prf_raddr1 := 0.U.asTypeOf(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.PRF_DEPTH).W)))
    io.prf_raddr2 := 0.U.asTypeOf(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.PRF_DEPTH).W)))

    val flush = Wire(Bool())
    flush := io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
    when(flush){
        for (i <- 0 until p.EXUISSUE_DEPTH){
            issue_queue(i).busy := false.B
            issue_queue(i).ready1 := false.B
            issue_queue(i).ready2 := false.B
        }
    }.otherwise{
        //来自Dispatch的入队指令
        when(VALID && dis_uop(0).valid && !dis_uop(1).valid){
            payload(dis_uop(0).bits.iq_index) := dis_uop(0).bits
            issue_queue(dis_uop(0).bits.iq_index).busy := true.B
            issue_queue(dis_uop(0).bits.iq_index).instr_type := dis_uop(0).bits.instr_type
            issue_queue(dis_uop(0).bits.iq_index).ps1 := dis_uop(0).bits.ps1
            issue_queue(dis_uop(0).bits.iq_index).ps2 := dis_uop(0).bits.ps2

            //入队时判断ps1和ps2的ready信号
            //alu写回
            val alu_conditions1 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === dis_uop(0).bits.ps1)
            }
            val alu_conditions2 = for (i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)) yield {
                (io.wb_uop1(i).valid && io.wb_uop1(i).bits.pdst === dis_uop(0).bits.ps2)
            }
            //bu写回
            val bu_conditions1 = for (i <- 0 until p.BU_NUM ) yield {
                (io.bu_wb_uop(i).valid && !io.bu_wb_uop(i).bits.is_conditional && io.bu_wb_uop(i).bits.pdst === dis_uop(0).bits.ps1)
            }
            val bu_conditions2 = for (i <- 0 until p.BU_NUM) yield {
                (io.bu_wb_uop(i).valid && !io.bu_wb_uop(i).bits.is_conditional && io.bu_wb_uop(i).bits.pdst === dis_uop(0).bits.ps2)
            }
            when (alu_conditions1.reduce(_ || _) || bu_conditions1.reduce(_ || _) || io.prf_valid(dis_uop(0).bits.ps1) || (dis_uop(0).bits.fu_signals.opr1_sel =/= OprSel.REG)) {
                issue_queue(dis_uop(0).bits.iq_index).ready1 := true.B
            } .otherwise{
                issue_queue(dis_uop(0).bits.iq_index).ready1 := false.B
            }
            when (alu_conditions2.reduce(_ || _) || bu_conditions2.reduce(_ || _) || io.prf_valid(dis_uop(0).bits.ps2) || (dis_uop(0).bits.fu_signals.opr2_sel =/= OprSel.REG)) {
                issue_queue(dis_uop(0).bits.iq_index).ready2 := true.B
            } .otherwise{
                issue_queue(dis_uop(0).bits.iq_index).ready2 := false.B
            }
            //结束ready信号赋值
        } .elsewhen(VALID && dis_uop(0).valid && dis_uop(1).valid){
            for (k <- 0 until 2){
                payload(dis_uop(k).bits.iq_index) := dis_uop(k).bits
                issue_queue(dis_uop(k).bits.iq_index).busy := true.B
                issue_queue(dis_uop(k).bits.iq_index).instr_type := dis_uop(k).bits.instr_type
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
                    (io.bu_wb_uop(i).valid && !io.bu_wb_uop(i).bits.is_conditional && io.bu_wb_uop(i).bits.pdst === dis_uop(k).bits.ps1)
                }
                val bu_conditions2 = for (i <- 0 until p.BU_NUM) yield {
                    (io.bu_wb_uop(i).valid && !io.bu_wb_uop(i).bits.is_conditional && io.bu_wb_uop(i).bits.pdst === dis_uop(k).bits.ps2)
                }
                when (alu_conditions1.reduce(_ || _) || bu_conditions1.reduce(_ || _) || io.prf_valid(dis_uop(k).bits.ps1) || (dis_uop(k).bits.fu_signals.opr1_sel =/= OprSel.REG)) {
                    issue_queue(dis_uop(k).bits.iq_index).ready1 := true.B
                } .otherwise{
                    issue_queue(dis_uop(k).bits.iq_index).ready1 := false.B
                }
                when (alu_conditions2.reduce(_ || _) || bu_conditions2.reduce(_ || _) || io.prf_valid(dis_uop(k).bits.ps2) || (dis_uop(k).bits.fu_signals.opr2_sel =/= OprSel.REG)) {
                    issue_queue(dis_uop(k).bits.iq_index).ready2 := true.B
                } .otherwise{
                    issue_queue(dis_uop(k).bits.iq_index).ready2 := false.B
                }
                //结束ready信号赋值
            }
        }.otherwise{
            //不入队
        }

        //每周期监听后续寄存器的ready信号，更新ready状态
        for (i <- 0 until p.EXUISSUE_DEPTH){
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
    val select = Module(new exu_iq_select_logic())
    val select_index = Wire(Vec(2, Valid(UInt(log2Ceil(p.EXUISSUE_DEPTH).W))))
    select.io.mul_ready := io.mul_ready
    select.io.div_ready := io.div_ready
    select.io.issue_queue := issue_queue
    select_index := select.io.sel_index
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
        when (!flush && select_index(i).valid){
            io.exu_issued_index(i).valid := true.B
            io.exu_issued_index(i).bits := select_index(i).bits
        }.otherwise{
            io.exu_issued_index(i).valid := false.B
            io.exu_issued_index(i).bits := 0.U
        }
    }
    //发射命令到级间寄存器
    val issue_to_exu = Module(new issue2exu())
    issue_to_exu.io.flush := flush
    for (i <- 0 until 2){
        when (!flush && select_index(i).valid){
            issue_to_exu.io.if_valid(i) := true.B
            issue_queue(select_index(i).bits).busy := false.B
            issue_queue(select_index(i).bits).ready1 := false.B
            issue_queue(select_index(i).bits).ready2 := false.B
        }.otherwise{
            issue_to_exu.io.if_valid(i) := false.B
        }
        issue_to_exu.io.dis_issue_uop(i) := payload(select_index(i).bits)
        issue_to_exu.io.ps1_value(i) := io.ps1_value(i)
        issue_to_exu.io.ps2_value(i) := io.ps2_value(i)
        io.execute_uop(i) := issue_to_exu.io.execute_uop(i)


    }
}
