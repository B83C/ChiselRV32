package rsd_rv32.execution

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import rsd_rv32.scheduler._
import rsd_rv32.common._

//该Bundle用于后续向Arbiter传输信号,不是interface
class Req_Abter(implicit p: Parameters) extends CustomBundle {

  val func3     = UInt(3.W) //访存指令的fun3字段
  val data      = UInt(32.W)
  val data_Addr = UInt(32.W)
  val write_en  = Bool()

}

class LSUIO(implicit p: Parameters) extends CustomBundle {
  //with MEM
  val data_addr     = Output(UInt(64.W))//访存指令的目标地址
  val data_into_mem = Output(UInt(64.W))//需要写入储存器的数据
  val write_en      = Output(Bool())//写使能信号

  val func3 = Output(UInt(3.W))//访存指令的fun3字段

  val data_out_mem  = Flipped(UInt(64.W))//从储存器中读取的数据

  //with store issue queue
  val store_uop = Flipped(Valid(new STISSUE_STPIPE_uop()))//存储指令的uop

  //with load issue queue
  val load_uop  = Flipped(Decoupled(new LDISSUE_LDPIPE_uop()))//加载指令的uop

  //with dispatcher
  val stq_tail  = Output(UInt(log2Ceil(p.STQ_DEPTH).W))//stq的尾部索引 
  val stq_head  = Output(UInt(log2Ceil(p.STQ_DEPTH).W))//stq的头部索引
  val stq_full  = Output(Bool())//stq是否为满,1表示满
  val st_cnt = Flipped(UInt(log2Ceil(p.CORE_WIDTH + 1).W))//存储指令被派遣的情况(00表示没有，01表示派遣一条，11表示派遣两条)，用于更新store queue（在lsu中）的tail（full标志位）
  
  //with ROB
  val rob_commitsignal = Flipped(Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))))//ROB的CommitSignal信号
  val stu_wb_uop = Valid((new STPIPE_WB_uop()))//存储完成的信号,wb to ROB
  val ldu_wb_uop  = Valid((new ALU_WB_uop()))//加载完成的信号,wb to ROB and PRF
}

//PipelineReg模块，用于将数据从一个阶段传递到下一个阶段
class PipelineReg(val width: Int) extends CustomModule {
  val io = IO(new Bundle {
    val stall_in = Flipped(Bool())
    val data_in  = Flipped(UInt(width.W))
    val data_out = Output(UInt(width.W))
    val reset = Flipped(Bool())
  })
  val reg = RegInit(0.U(width.W))


  when(io.reset) {
    reg := 0.U
  }.elsewhen(!io.reset && io.stall_in) {
    reg := reg
  }otherwise{
    reg := io.data_in
  }


  io.data_out := reg
}

//LSU的arbiter模块，用于将stq和ldq的请求信号进行仲裁，选择一个信号传递给存储器
class LSUArbiter(implicit p: Parameters) extends CustomModule {
  val io = IO(new Bundle {
    val stqReq  = Flipped(Decoupled(new Req_Abter()))//stq的请求信号
    val ldReq   = Flipped(Decoupled(new Req_Abter()))//ldq的请求信号

    val memOut = Decoupled(new Req_Abter())//arbiter的输出信号，传递给存储器

    val isStore = Output(Bool())//是否为存储请求信号
  })
  val arb = Module(new RRArbiter(new Req_Abter(), 2))//创建一个arbiter模块，输入信号为两个请求信号，输出信号为存储器的请求信号

  //接入请求信号
  arb.io.in(0) <> io.stqReq//将stq的请求信号连接到arbiter的输入端口0
  arb.io.in(1) <> io.ldReq//将ldq的请求信号连接到arbiter的输入端口1

  //输出信号连接
  io.memOut.valid   := arb.io.out.valid//arbiter的输出信号连接到存储器的请求信号
  io.memOut.bits    := arb.io.out.bits//将arbiter的输出信号连接到存储器的请求信号
  arb.io.out.ready  := true.B//MEM总是准备好接收数据

  io.isStore := arb.io.out.bits.write_en && io.memOut.valid//将arbiter的输出信号连接到存储器的请求信号

//  printf(p"storeready=${Binary(io.stqReq.ready)} loadready=${Binary(io.ldReq.ready)}\n")
//  printf(p"outdata${Hexadecimal(io.memOut.bits.data)}\n")
//  printf(p"isStore=${Binary(io.isStore)}\n\n")

}


//LSU的加载管线模块，用于处理加载指令的执行
class LoadPipeline(implicit p: Parameters) extends CustomModule {
  val io = IO(new Bundle {
    val load_uop = Flipped(Decoupled(new LDISSUE_LDPIPE_uop()))//加载指令的uop
    val ldu_wb_uop  = Valid((new ALU_WB_uop()))//加载完成的信号,wb to ROB and PRF

    val addr_search_stq = Valid(UInt(p.XLEN.W))//地址搜索信号,进入stq的搜索地址
    val func3 = Output(UInt(3.W))//fun3信号
    val stq_tail = Output(UInt(log2Ceil(p.STQ_DEPTH).W))//stq的尾部索引
    

    val ldReq = Decoupled(new Req_Abter())//加载请求信号

    val data_out_mem = Flipped(UInt(64.W))//从储存器中读取的数据
    val data_out_stq = Flipped(new STQEntry())//从stq中读取的数据

    val rob_commitsignal = Flipped(Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))))//ROB的CommitSignal信号
  })
  val need_flush = Wire(Bool())
  need_flush := io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
  val stage1_pipevalid = io.load_uop.valid
  val stall = Wire(Bool())

  //stage1地址计算
  //stage1-stage2之间的PipelineReg
  val Stage1ToStage2_ldAddr_reg = Module(new PipelineReg(p.XLEN))
  val Stage1ToStage2_func3_reg = Module(new PipelineReg(3))
  val Stage1ToStage2_pipevalid_reg = Module(new PipelineReg(1))
  val Stage1ToStage2_stqtail_reg = Module(new PipelineReg(log2Ceil(p.STQ_DEPTH)))
  val Stage1ToStage2_robidx_reg = Module(new PipelineReg(log2Ceil(p.ROB_DEPTH)))
  val Stage1ToStage2_pdst_reg  = Module(new PipelineReg(log2Ceil(p.PRF_DEPTH)))



  val instr = io.load_uop.bits.instr
  val ps1_value = io.load_uop.bits.ps1_value


  val imm_temp = instr(24, 13)
  val stage1_imm = Cat(Fill(20,imm_temp(11)),imm_temp)
  val stage1_func3 = instr(7, 5)

  val stage1_ldAddr = ps1_value + stage1_imm



  Stage1ToStage2_ldAddr_reg.io.data_in := stage1_ldAddr
  Stage1ToStage2_func3_reg.io.data_in := stage1_func3
  Stage1ToStage2_pipevalid_reg.io.data_in := stage1_pipevalid.asUInt
  Stage1ToStage2_stqtail_reg.io.data_in := io.load_uop.bits.stq_tail
  Stage1ToStage2_robidx_reg.io.data_in := io.load_uop.bits.rob_index
  Stage1ToStage2_pdst_reg.io.data_in := io.load_uop.bits.pdst


  Stage1ToStage2_ldAddr_reg.io.stall_in := stall
  Stage1ToStage2_func3_reg.io.stall_in  := stall
  Stage1ToStage2_stqtail_reg.io.stall_in := stall
  Stage1ToStage2_robidx_reg.io.stall_in := stall
  Stage1ToStage2_pdst_reg.io.stall_in := stall
  Stage1ToStage2_pipevalid_reg.io.stall_in := stall

  Stage1ToStage2_ldAddr_reg.io.reset := need_flush
  Stage1ToStage2_func3_reg.io.reset := need_flush
  Stage1ToStage2_pipevalid_reg.io.reset := need_flush
  Stage1ToStage2_stqtail_reg.io.reset := need_flush
  Stage1ToStage2_robidx_reg.io.reset := need_flush
  Stage1ToStage2_pdst_reg.io.reset := need_flush


//stage2
  val stage2_ldAddr = Stage1ToStage2_ldAddr_reg.io.data_out
  val stage2_func3 = Stage1ToStage2_func3_reg.io.data_out
  val stage2_stqtail = Stage1ToStage2_stqtail_reg.io.data_out
  val stage2_pipevalid = Stage1ToStage2_pipevalid_reg.io.data_out.asBool
  val stage2_robidx = Stage1ToStage2_robidx_reg.io.data_out
  val stage2_pdst = Stage1ToStage2_pdst_reg.io.data_out


  //向STQ发起forwarding
  io.addr_search_stq.bits := stage2_ldAddr
  io.func3    :=  stage2_func3
  io.stq_tail := stage2_stqtail

  io.addr_search_stq.valid := stage2_pipevalid && (!need_flush) && (!stall)

  //STQ forwarding传回的结果
  val stage2_data_bitvalid = io.data_out_stq.bit_valid
  val stage2_data_out_stq = io.data_out_stq.data

  val expected = MuxCase(false.B, Seq(
      (stage2_func3 === 0.U) -> (stage2_data_bitvalid(7,0) === "hFF".U),    // LB
      (stage2_func3 === 1.U) -> (stage2_data_bitvalid(15,0) === "hFFFF".U),  // LH
      (stage2_func3 === 2.U) -> (stage2_data_bitvalid(31,0) === "hFFFFFFFF".U), // LW
      (stage2_func3 === 4.U) -> (stage2_data_bitvalid(7,0) === "hFF".U),     // LBU
      (stage2_func3 === 5.U) -> (stage2_data_bitvalid(15,0) === "hFFFF".U)  // LHU
    ))
    //我们期望的bitvalid值，与stq传出的bitvalid值进行比较，决定后续是否需要向Abter发起访存请求

  //只有当发起请求，但请求尚未被RRArbiter接收的时候发生stall

  when(!io.ldReq.ready && io.ldReq.valid){
    stall := true.B
  }.otherwise{
    stall := false.B
  }

  //向RRArbiter发起写入申请
  io.ldReq.bits.data := 0.U
  io.ldReq.bits.data_Addr := stage2_ldAddr
  io.ldReq.bits.func3 := stage2_func3
  io.ldReq.bits.write_en := false.B
  io.ldReq.valid := stage2_pipevalid && (!need_flush) && (!expected)

//  printf(p"addr=${Hexadecimal(stage2_ldAddr)} data=${Hexadecimal(stage2_data_out_stq)} bitvalid=${Hexadecimal(stage2_data_bitvalid)}\n")
//  printf(p"ldReq.valid=${Binary(io.ldReq.valid)} stall=${Binary(stall)}\n")
  io.load_uop.ready := (!need_flush) && (!stall)



  //stage2-stage3之间的PipelineReg
  
  val Stage2ToStage3_data_reg = Module(new PipelineReg(p.XLEN))
  val Stage2ToStage3_bitvalid_reg = Module(new PipelineReg(p.XLEN))
  val Stage2ToStage3_pdst_reg = Module(new PipelineReg(log2Ceil(p.PRF_DEPTH)))
  val Stage2ToStage3_pipevalid_reg = Module(new PipelineReg(1))
  val Stage2ToStage3_robidx_reg = Module(new PipelineReg(log2Ceil(p.ROB_DEPTH)))
  val Stage2ToStage3_func3_reg  = Module(new PipelineReg(3))
  
  Stage2ToStage3_data_reg.io.data_in := stage2_data_out_stq
  Stage2ToStage3_bitvalid_reg.io.data_in := stage2_data_bitvalid
  Stage2ToStage3_pdst_reg.io.data_in := stage2_pdst
  Stage2ToStage3_pipevalid_reg.io.data_in := stage2_pipevalid.asUInt
  Stage2ToStage3_robidx_reg.io.data_in := stage2_robidx
  Stage2ToStage3_func3_reg.io.data_in := stage2_func3

  Stage2ToStage3_data_reg.io.stall_in := stall
  Stage2ToStage3_bitvalid_reg.io.stall_in := stall
  Stage2ToStage3_pdst_reg.io.stall_in := stall
  Stage2ToStage3_pipevalid_reg.io.stall_in := stall
  Stage2ToStage3_robidx_reg.io.stall_in := stall
  Stage2ToStage3_func3_reg.io.stall_in := stall

  Stage2ToStage3_data_reg.io.reset := need_flush
  Stage2ToStage3_bitvalid_reg.io.reset := need_flush
  Stage2ToStage3_pdst_reg.io.reset := need_flush
  Stage2ToStage3_pipevalid_reg.io.reset := need_flush
  Stage2ToStage3_robidx_reg.io.reset := need_flush
  Stage2ToStage3_func3_reg.io.reset := need_flush
  

//stage3进行mem和stq的数据合并
  val stage3_data = Stage2ToStage3_data_reg.io.data_out
  val stage3_bitvalid = Stage2ToStage3_bitvalid_reg.io.data_out
  
  val data_out_mem = Wire(UInt(p.XLEN.W))
  data_out_mem := io.data_out_mem
  val data_merged = (stage3_data & stage3_bitvalid) | (data_out_mem & (~stage3_bitvalid).asUInt)
  val final_data = WireDefault(UInt(p.XLEN.W),0.U)

  switch(Stage2ToStage3_func3_reg.io.data_out){
    is(0.U){ //LB
      final_data := Cat(Fill(24,data_merged(7)),data_merged(7,0))
    }
    is(1.U){ //LH
      final_data := Cat(Fill(16,data_merged(15)),data_merged(15,0))
    }
    is(2.U){ //LW
      final_data := data_merged
    }
    is(4.U){ //LBU
      final_data := Cat(0.U(24.W),data_merged(7,0))
    }
    is(5.U){ //LHU
      final_data := Cat(0.U(16.W),data_merged(15,0))
    }
  }

//  printf(p"finaldata=${Hexadecimal(final_data)}\n")
//stage3-stage4的PipelineReg
  val Stage3ToStage4_data_reg = Module(new PipelineReg(p.XLEN))
  val Stage3ToStage4_pdst_reg = Module(new PipelineReg(log2Ceil(p.PRF_DEPTH)))
  val Stage3ToStage4_pipevalid_reg = Module(new PipelineReg(1))
  val Stage3ToStage4_robidx_reg = Module(new PipelineReg(log2Ceil(p.ROB_DEPTH)))

  Stage3ToStage4_data_reg.io.data_in := final_data
  Stage3ToStage4_pdst_reg.io.data_in := Stage2ToStage3_pdst_reg.io.data_out
  Stage3ToStage4_pipevalid_reg.io.data_in := Stage2ToStage3_pipevalid_reg.io.data_out
  Stage3ToStage4_robidx_reg.io.data_in := Stage2ToStage3_robidx_reg.io.data_out

  Stage3ToStage4_data_reg.io.stall_in := stall
  Stage3ToStage4_pdst_reg.io.stall_in := stall
  Stage3ToStage4_pipevalid_reg.io.stall_in := stall
  Stage3ToStage4_robidx_reg.io.stall_in := stall

  Stage3ToStage4_data_reg.io.reset := need_flush
  Stage3ToStage4_pdst_reg.io.reset := need_flush
  Stage3ToStage4_pipevalid_reg.io.reset := need_flush
  Stage3ToStage4_robidx_reg.io.reset := need_flush
  
//stage4进行wb操作

  io.ldu_wb_uop.valid := Stage3ToStage4_pipevalid_reg.io.data_out.asBool && (!need_flush) && (!stall)
  //仅当ld_issue_uop传入有效且不需要flush时wb rob的uop才有效
  io.ldu_wb_uop.bits.rob_index := Stage3ToStage4_robidx_reg.io.data_out
  io.ldu_wb_uop.bits.pdst := Stage3ToStage4_pdst_reg.io.data_out
  io.ldu_wb_uop.bits.pdst_value := Stage3ToStage4_data_reg.io.data_out

//  printf(p"valid=${Binary(io.ldu_wb_uop.valid)} robidx=${Hexadecimal(io.ldu_wb_uop.bits.rob_index)}\npdst=${Hexadecimal(io.ldu_wb_uop.bits.pdst)} pdst_value=${Hexadecimal(io.ldu_wb_uop.bits.pdst_value)}\n" )
//  printf(p"needflush=${Binary(need_flush)}")
//  printf(p"\n")
}


class StorePipeline(implicit p: Parameters) extends CustomModule {
  val io = IO(new Bundle {
    val store_uop = Flipped(Valid(new STISSUE_STPIPE_uop()))//存储指令的uop
    val stu_wb_uop = Valid(new STPIPE_WB_uop())//存储完成的信号,wb to ROB

    val data_into_stq = Output(UInt(p.XLEN.W))//需要写入stq的数据
    val dataAddr_into_stq = Valid(UInt(p.XLEN.W))//需要写入stq的地址
    val func3 = Output(UInt(3.W))//fun3信号
    val stq_index = Output(UInt(log2Ceil(p.STQ_DEPTH).W))//需要写入stq的索引

    val rob_commitsignal = Flipped(Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))))//ROB的CommitSignal信号
  })
  val need_flush = io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
//stage1地址计算
  val instr = Wire(UInt((p.XLEN - 7).W))
  val ps1_value = Wire(UInt(p.XLEN.W))

  instr := io.store_uop.bits.instr
  ps1_value := io.store_uop.bits.ps1_value
  
  val imm_high = instr(24, 18)
  val imm_low = instr(4, 0)
  val stage1_func3 = instr(7, 5)

  val stage1_imm = Cat(Fill(20,imm_high(6)),imm_high, imm_low)

  val stage1_dataAddr = ps1_value + stage1_imm

  val pipeline_valid = Wire(Bool())
  pipeline_valid := io.store_uop.valid
  


//下面都是PipelineReg的操作，stage1-stage2之间的pipelinereg
  val Stage1ToStage2_data_reg = Module(new PipelineReg(p.XLEN))
  val Stage1ToStage2_addr_reg = Module(new PipelineReg(p.XLEN))
  val Stage1ToStage2_stqidx_reg = Module(new PipelineReg(log2Ceil(p.STQ_DEPTH)))
  val Stage1ToStage2_robidx_reg = Module(new PipelineReg(log2Ceil(p.ROB_DEPTH)))
  val Stage1ToStage2_func3_reg = Module(new PipelineReg(3))
  val Stage1ToStage2_valid_reg = Module(new PipelineReg(1))
  
  Stage1ToStage2_data_reg.io.data_in := io.store_uop.bits.ps2_value
  Stage1ToStage2_addr_reg.io.data_in := stage1_dataAddr
  Stage1ToStage2_stqidx_reg.io.data_in := io.store_uop.bits.stq_index
  Stage1ToStage2_robidx_reg.io.data_in := io.store_uop.bits.rob_index
  Stage1ToStage2_valid_reg.io.data_in  := io.store_uop.valid.asUInt
  Stage1ToStage2_func3_reg.io.data_in  := stage1_func3

  Stage1ToStage2_data_reg.io.stall_in := false.B
  Stage1ToStage2_addr_reg.io.stall_in := false.B
  Stage1ToStage2_stqidx_reg.io.stall_in := false.B
  Stage1ToStage2_robidx_reg.io.stall_in := false.B
  Stage1ToStage2_valid_reg.io.stall_in  := false.B
  Stage1ToStage2_func3_reg.io.stall_in  := false.B

  Stage1ToStage2_data_reg.io.reset := need_flush
  Stage1ToStage2_addr_reg.io.reset := need_flush
  Stage1ToStage2_stqidx_reg.io.reset := need_flush
  Stage1ToStage2_robidx_reg.io.reset := need_flush
  Stage1ToStage2_valid_reg.io.reset := need_flush
  Stage1ToStage2_func3_reg.io.reset := need_flush

//stage2 wb to ROB and STQ
  val stage2_data = Stage1ToStage2_data_reg.io.data_out
  val stage2_addr = Stage1ToStage2_addr_reg.io.data_out
  val stage2_stqidx = Stage1ToStage2_stqidx_reg.io.data_out 
  val stage2_func3 = Stage1ToStage2_func3_reg.io.data_out




  //为1的时候表示需要进行flush，即将传入stq的全部数取0

  io.data_into_stq     := stage2_data 
  io.dataAddr_into_stq.bits := stage2_addr
  io.stq_index         := stage2_stqidx
  io.func3             := stage2_func3


  
  io.dataAddr_into_stq.valid := !need_flush && Stage1ToStage2_valid_reg.io.data_out.asBool


  io.stu_wb_uop.valid  := Stage1ToStage2_valid_reg.io.data_out.asBool && (!need_flush)
  //仅当st_issue_uop传入有效且不需要flush时wb rob的uop才有效
  io.stu_wb_uop.bits.rob_index := Stage1ToStage2_robidx_reg.io.data_out
//  printf(p"data=${Hexadecimal(stage2_data)} addr=${Hexadecimal(stage2_addr)}\n")
//  printf(p"stqidx=${Hexadecimal(io.stq_index)} func3=${Hexadecimal(io.func3)}\n")
//  printf(p"needflush=${Binary(need_flush)}\n")
//  printf(p"valid=${Binary(io.stu_wb_uop.valid)} robidx=${Hexadecimal(io.stu_wb_uop.bits.rob_index)}\n\n")
}
//stq模块

class STQEntry(implicit p: Parameters) extends Bundle {
  val data = UInt(p.XLEN.W)
  val data_Addr = UInt(p.XLEN.W)
  val bit_valid = UInt(p.XLEN.W)
  val func3 = UInt(3.W)
}

class StoreQueue(implicit p: Parameters) extends CustomModule {
  val io = IO(new Bundle {
    val stq_full = Output(Bool())//stq是否为满,1表示满
    val stq_tail = Output(UInt(log2Ceil(p.STQ_DEPTH).W))//stq的尾部索引 
    val stq_head = Output(UInt(log2Ceil(p.STQ_DEPTH).W))//stq的头部索引

    val input_tail = Flipped(UInt(log2Ceil(p.STQ_DEPTH).W))//输入的tail指针，用于后续的查找
    val addr_search_stq = Flipped(Valid(UInt(p.XLEN.W)))//地址搜索信号,进入stq的搜索地址
    val ld_func3 = Flipped(UInt(3.W))//fun3信号

    val searched_data = Output(new STQEntry)//从stq中读取的数据

    val stqReq = Decoupled(new Req_Abter())//存储请求信号

    val st_cnt = Flipped(UInt(log2Ceil(p.CORE_WIDTH + 1).W))//用于更新store queue（在lsu中）的tail（full标志位）
    val rob_commitsignal = Flipped(Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))))//ROB的CommitSignal信号

    val dataAddr_into_stq = Flipped(Valid(UInt(p.XLEN.W)))//需要写入stq的地址
    val data_into_stq = Flipped(UInt(p.XLEN.W))//需要写入stq的数据
    val stq_index = Flipped(UInt(log2Ceil(p.STQ_DEPTH+1).W))//需要写入stq的索引
    val st_func3 = Flipped(UInt(3.W))//fun3信号
  })   

  //flush信号，作flush的时候tail指针回到write_valid所处的地方
  //当rob的commit信号为valid且mispred为1时，表示需要flush
  val need_flush = Wire(Bool())
  need_flush := io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
  //初始化stq的entrie

  val stq_entries = RegInit(VecInit(Seq.fill(p.STQ_DEPTH){
    (new STQEntry).Lit(
      _.data -> 0.U,
      _.data_Addr -> 0.U,
      _.bit_valid -> 0.U,
      _.func3 -> 0.U
    )
  }))


//stq的head和tail指针
  val head = RegInit(0.U(log2Ceil(p.STQ_DEPTH).W))
  val tail = RegInit(0.U(log2Ceil(p.STQ_DEPTH).W))
  val write_valid = RegInit(0.U(log2Ceil(p.STQ_DEPTH).W))

  //调试用，忽略
//  printf(p"head=${head} write_valid=${write_valid} tail=${tail}\n")
//  for (i <- 0 until p.STQ_DEPTH) {
//    val entry = stq_entries(i)
//    printf(p"STQ[${"%02d".format(i)}]: data=0x${Hexadecimal(entry.data)} addr=0x${Hexadecimal(entry.data_Addr)} bits_valid=0x${Hexadecimal(entry.bit_valid)} func3=0x${Hexadecimal(entry.func3)}\n")
//  }
//  printf(p"\n")

  //更新指针位置
  def nextPtr(ptr: UInt, inc: UInt): UInt = {
    val next = ptr + inc
    Mux(next >= p.STQ_DEPTH.U, next - p.STQ_DEPTH.U, next)
  }

  val tail_inc  = io.st_cnt
  val tail_next = nextPtr(tail, tail_inc)


  //更新write_valid指针

  when(need_flush){
    write_valid := write_valid
  }.otherwise{
    when(!io.rob_commitsignal(0).valid) {
      write_valid := write_valid
    }.elsewhen(io.rob_commitsignal(0).valid && !io.rob_commitsignal(1).valid){
      when(io.rob_commitsignal(0).bits.rob_type === ROBType.Store){
        write_valid := nextPtr(write_valid, 1.U)
      }.otherwise{
        write_valid := write_valid
      }
    }.otherwise{
      when((io.rob_commitsignal(0).bits.rob_type === ROBType.Store) &&
        io.rob_commitsignal(1).bits.rob_type === ROBType.Store){
        write_valid := nextPtr(write_valid,2.U)
      }.elsewhen((io.rob_commitsignal(0).bits.rob_type === ROBType.Store)^
        (io.rob_commitsignal(1).bits.rob_type === ROBType.Store)){
        write_valid := nextPtr(write_valid,1.U)
      }.otherwise{
        write_valid := write_valid
      }
    }
  }

  //tail的移动
  when((tail_inc =/= 0.U) && (!need_flush)) {
    tail := tail_next
  }.elsewhen(need_flush) {
    tail := write_valid
  }


  //执行storepipeline向stq写入的操作
  when(!need_flush && io.dataAddr_into_stq.valid){


    //写入stq的操作

    val stq_wb_idx = Wire(UInt(log2Ceil(p.STQ_DEPTH).W))
    stq_wb_idx := io.stq_index
    stq_entries(stq_wb_idx).data := io.data_into_stq
    stq_entries(stq_wb_idx).data_Addr := io.dataAddr_into_stq.bits
    stq_entries(stq_wb_idx).func3 := io.st_func3
    stq_entries(stq_wb_idx).bit_valid := MuxCase(0.U, Seq(
      (io.st_func3 === 0.U) -> ("hFF".U),    // SB
      (io.st_func3 === 1.U) -> ("hFFFF".U),  // SH
      (io.st_func3 === 2.U) -> ("hFFFFFFFF".U) // SW
    ))

  }


  //执行向仲裁器发起写入mem内存请求的功能
  when(!need_flush) {
    //发送向Arbiter的信号
    val head_entry = stq_entries(head)
    val head_valid = (head =/= write_valid)

    io.stqReq.valid := head_valid
    io.stqReq.bits.data  := head_entry.data
    io.stqReq.bits.data_Addr := head_entry.data_Addr
    io.stqReq.bits.func3 := head_entry.func3
    io.stqReq.bits.write_en  := true.B

    when(io.stqReq.ready && io.stqReq.valid) {
      head := nextPtr(head, 1.U)
      stq_entries(head).bit_valid := 0.U
    }


  }.otherwise{
    io.stqReq.valid := false.B
    io.stqReq.bits.data  := 0.U
    io.stqReq.bits.data_Addr := 0.U
    io.stqReq.bits.func3 := 0.U
    io.stqReq.bits.write_en  := true.B
  }

  val stq_full = RegInit(false.B)
  stq_full := (tail_next === head) && (!need_flush)


  io.stq_full := stq_full
  io.stq_tail := tail
  io.stq_head := head


  //用于判断当前的stq_idx是否在head和tail之间的有效范围内
  def isInrange(idx: UInt,head: UInt, tail: UInt): Bool = {
    Mux(tail >= head, idx >= head && idx < tail, idx >= head || idx < tail)
  }






  //暂存在forwarding过程中的部分变量
  val SearchAddr = Wire(UInt(p.XLEN.W))
  val byteSearched = WireDefault(Vec(4, Vec(p.STQ_DEPTH,Bool())), 0.U.asTypeOf(Vec(4, Vec(p.STQ_DEPTH,Bool()))))
  val found_data = WireDefault(Vec(4,UInt(8.W)), 0.U.asTypeOf(Vec(4,UInt(8.W))))
  val found_data_bytevalid = WireDefault(Vec(4,Bool()), 0.U.asTypeOf(Vec(4,Bool())))

  SearchAddr := io.addr_search_stq.bits
  //我们最后forwarding的结果，高位未填充的时候都是0


  //掩码，用于判断对应的byte是否是valid
  val mask = WireDefault(UInt(4.W), 0.U)
  switch(io.ld_func3){
    is(0.U){
      mask := "b0001".U
    }
    is(1.U){
      mask := "b0011".U
    }
    is(2.U){
      mask := "b1111".U
    }
    is(4.U){
      mask := "b0001".U
    }
    is(5.U){
      mask := "b0011".U
    }
  }

  //stq的搜索操作，用于loadpipeline的forwarding
  when(!need_flush && io.addr_search_stq.valid){
    for(delta_byte <- 0 until 4){
      val curr_addr = SearchAddr + delta_byte.U
      for(i <- 0 until p.STQ_DEPTH){
        val entry = stq_entries(i)
        val store_byte = Mux(entry.func3 === 0.U, 1.U,
          Mux(entry.func3 === 1.U, 2.U,
            Mux(entry.func3 === 2.U, 4.U, 0.U)))
        byteSearched(delta_byte)(i) := isInrange(i.U,head, io.input_tail) &&
          (curr_addr >= entry.data_Addr) && (curr_addr < (entry.data_Addr + store_byte)) && mask(delta_byte).asBool
      }
    }


    for (delta_byte <- 0 until 4){
      val rev_byteSearch =  VecInit((0 until p.STQ_DEPTH).map { i =>
        val idx = (head + p.STQ_DEPTH.U - 1.U - i.U) % p.STQ_DEPTH.U
        byteSearched(delta_byte)(idx)
      })
      val anyHit = rev_byteSearch.asUInt.orR
      val sel    = PriorityEncoder(rev_byteSearch)
      found_data_bytevalid(delta_byte) := anyHit
      // 提取对应 Store 的一个字节
      val realIdx = (head + p.STQ_DEPTH.U -1.U - sel) % p.STQ_DEPTH.U
      val entry = stq_entries(realIdx)
      val offset = SearchAddr + delta_byte.asUInt - entry.data_Addr
      found_data(delta_byte) := (entry.data >> (offset << 3)).asUInt(7, 0)
    }
  }


  val found_data_bitvalid = Wire(UInt(p.XLEN.W))
  found_data_bitvalid := Cat(
    Fill(8,found_data_bytevalid(3).asUInt),
    Fill(8,found_data_bytevalid(2).asUInt),
    Fill(8,found_data_bytevalid(1).asUInt),
    Fill(8,found_data_bytevalid(0).asUInt)
  )

  io.searched_data.data := found_data.asUInt
  io.searched_data.data_Addr := io.addr_search_stq.bits
  io.searched_data.func3 := io.ld_func3
  io.searched_data.bit_valid := found_data_bitvalid

//  printf(p"founddata=${Hexadecimal(io.searched_data.data)} bitvalid=${Hexadecimal(io.searched_data.bit_valid)}\n")
//  printf(p"\n")

}
//LSU的模块定义，目前只完成了IO接口的定义，内部逻辑还未完成
class LSU(implicit p: Parameters) extends CustomModule {

    val io = IO(new LSUIO())//定义LSU的IO接口

    val arbiter = Module(new LSUArbiter())//创建一个arbiter模块
    val load_pipeline = Module(new LoadPipeline())//创建一个加载管线模块
    val store_pipeline = Module(new StorePipeline())//创建一个存储管线模块
    val store_queue = Module(new StoreQueue())//创建一个存储队列模块

    //连接arbiter有关的信号
    val temp_DataToMem = Wire(UInt(64.W))
    val temp_AddrToMem = Wire(UInt(64.W))
    temp_AddrToMem := Cat(0.U(32.W), arbiter.io.memOut.bits.data_Addr(31, 0))
    temp_DataToMem := Cat(0.U(32.W), arbiter.io.memOut.bits.data(31, 0))
    io.data_addr := temp_AddrToMem//将地址信号连接到arbiter的输出端口
    io.data_into_mem := temp_DataToMem//将数据信号连接到arbiter的输出端口
    io.write_en := arbiter.io.isStore//将写使能信号连接到arbiter的输出端口
    io.func3 := arbiter.io.memOut.bits.func3//将fun3信号连接到arbiter的输出端口
    arbiter.io.ldReq <> load_pipeline.io.ldReq//将加载请求信号连接到arbiter的输入端口
    arbiter.io.stqReq <> store_queue.io.stqReq//将存储请求信号连接到arbiter的输入端口
    arbiter.io.memOut.ready := true.B


  //连接加载管线模块的信号
    load_pipeline.io.load_uop <> io.load_uop//将加载指令的uop连接到加载管线模块的输入端口
    load_pipeline.io.data_out_mem := io.data_out_mem(31,0)//将从存储器中读取的数据连接到加载管线模块的输入端口
    load_pipeline.io.addr_search_stq <> store_queue.io.addr_search_stq//将地址搜索信号连接到加载管线模块的输入端口
    load_pipeline.io.func3 <> store_queue.io.ld_func3
    load_pipeline.io.stq_tail <> store_queue.io.input_tail
    load_pipeline.io.data_out_stq <> store_queue.io.searched_data
    load_pipeline.io.rob_commitsignal := io.rob_commitsignal
    load_pipeline.io.ldu_wb_uop <> io.ldu_wb_uop

  //连接storepipeline的信号
    store_pipeline.io.store_uop := io.store_uop
    io.stu_wb_uop := store_pipeline.io.stu_wb_uop
    store_pipeline.io.data_into_stq <> store_queue.io.data_into_stq
    store_pipeline.io.dataAddr_into_stq <> store_queue.io.dataAddr_into_stq
    store_pipeline.io.func3 <> store_queue.io.st_func3
    store_pipeline.io.stq_index <> store_queue.io.stq_index
    store_pipeline.io.rob_commitsignal := io.rob_commitsignal

  //连接STQ的信号
    store_queue.io.st_cnt := io.st_cnt
    store_queue.io.rob_commitsignal := io.rob_commitsignal
    io.stq_head := store_queue.io.stq_head
    io.stq_tail := store_queue.io.stq_tail
    io.stq_full :=store_queue.io.stq_tail



}
