class st_issue_IO(implicit p: Parameters) extends Bundle {
    //来自Dispatch Unit的输入
    val iq_id = Input(Vec(p.DISPATCH_WIDTH, UInt(log2Ceil(p.IQ_DEPTH).W))) //IQ ID
    val dis_uop = Flipped(Valid(Vec(p.DISPATCH_WIDTH, new DISPATCH_STISSUE_uop())))  //来自Dispatch Unit的输入

    //发射到st的输出
    val issue_uop = Decoupled(new STISSUE_STPIPE_uop())  //发射的指令
    val value_o1 = Output(UInt(p.XLEN.W)) //发射的指令的操作数1
    val value_o2 = Output(UInt(p.XLEN.W)) //发射的指令的操作数2

    //PRF
    val raddr1 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址1
    val raddr2 = Output(UInt(log2Ceil(p.PRF_DEPTH).W)) //PRF读地址2
    val value_i1 = Input(UInt(p.XLEN.W)) //操作数1
    val value_i2 = Input(UInt(p.XLEN.W)) //操作数2    

    //监听PRF的valid信号用于更新ready状态
    val prf_valid = Input(Vec(p.PRF_DEPTH, Bool())) //PRF的valid信号
    //监听FU后级间寄存器内的物理寄存器ready信号
    val ALU_complete_uop2 = Flipped(Valid((Vec(p.ALU_NUM, new ALU_WB_uop()))))  //来自alu的uop
    val LDU_complete_uop2 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop
    //监听FU处物理寄存器的ready信号
    val ALU_complete_uop1 = Flipped(Valid((Vec(p.ALU_NUM, new ALU_WB_uop()))))  //来自alu的uop
    val LDU_complete_uop1 = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop

    //输出至Dispatch Unit的信号
    val iq_freelist_update = Output(UInt(log2Ceil(p.IQ_DEPTH).W)) //更新IQ Freelist
}

class st_issue_queue(implicit p: Parameters) extends Module {
    val io = IO(new st_issue_IO())
}