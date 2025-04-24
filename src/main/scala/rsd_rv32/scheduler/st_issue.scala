class st_issue_IO(implicit p: Parameters) extends Bundle {
    //来自Dispatch Unit的输入
    //val iq_id = Input(Vec(p.CORE_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //IQ ID
    val dis_uop = Flipped(Valid(Vec(p.CORE_WIDTH, new DISPATCH_STISSUE_uop())))  //来自Dispatch Unit的输入

    //发射到st的输出
    val st_issue_uop = Decoupled(new STISSUE_STPIPE_uop())  //发射的指令
    // val value_o1 = Output(UInt(p.XLEN.W)) //发射的指令的操作数1
    // val value_o2 = Output(UInt(p.XLEN.W)) //发射的指令的操作数2

    //PRF
    val st_issue_raddr1 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址1
    val st_issue_raddr2 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址2
    val st_issue_value1 = Input(UInt(p.XLEN.W)) //操作数1
    val st_issue_value2 = Input(UInt(p.XLEN.W)) //操作数2    

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
}

class st_issue_queue(implicit p: Parameters) extends Module {
    val io = IO(new st_issue_IO())
}