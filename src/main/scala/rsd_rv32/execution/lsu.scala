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

  val data_out_mem  = Input(UInt(64.W))//从储存器中读取的数据

  //with store issue queue
  val st_issue_uop = Flipped(Valid(new STISSUE_STPIPE_uop()))//存储指令的uop

  //with load issue queue
  val ld_issue_uop  = Flipped(Decoupled(new LDISSUE_LDPIPE_uop()))//加载指令的uop

  //with dispatcher
  val stq_tail  = Output(UInt(log2Ceil(p.STQ_DEPTH).W))//stq的尾部索引 
  val stq_head  = Output(UInt(log2Ceil(p.STQ_DEPTH).W))//stq的头部索引
  val stq_full  = Output(Bool())//stq是否为满,1表示满
  val st_dis = Input(Vec(p.CORE_WIDTH, Bool()))//存储指令被派遣的情况(00表示没有，01表示派遣一条，11表示派遣两条)，用于更新store queue（在lsu中）的tail（full标志位）
  
  //with ROB
  val rob_commitsignal = Input(Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))))//ROB的CommitSignal信号
  val stu_wb_uop = Valid((new STPIPE_WB_uop()))//存储完成的信号,wb to ROB
  val ldu_wb_uop  = Valid((new ALU_WB_uop()))//加载完成的信号,wb to ROB and PRF
}

//PipelineReg模块，用于将数据从一个阶段传递到下一个阶段
class PipelineReg(val width: Int) extends Module {
  val io = IO(new Bundle {
    val stall_in = Input(Bool())
    val data_in  = Input(UInt(width.W))
    val data_out = Output(UInt(width.W))
    val reset = Input(Bool())
  })
  val reg = withReset(reset){
    RegInit(0.U(width.W))
  }

  when(io.stall_in) {
    reg := reg
  }.otherwise {
    reg := io.data_in
  }

  io.data_out := reg
}

//LSU的arbiter模块，用于将stq和ldq的请求信号进行仲裁，选择一个信号传递给存储器
class LSUArbiter(implicit p: Parameters) extends Module {
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
  
}

//LSU的加载管线模块，用于处理加载指令的执行
class LoadPipeline(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val ld_issue_uop = Flipped(Decoupled(new LDISSUE_LDPIPE_uop()))//加载指令的uop
    val ldu_wb_uop  = Valid((new ALU_WB_uop()))//加载完成的信号,wb to ROB and PRF

    val addr_search_stq = Valid(UInt(p.XLEN.W))//地址搜索信号,进入stq的搜索地址
    val func3 = Valid(UInt(3.W))//fun3信号
    val stq_tail = Valid(UInt(log2Ceil(p.STQ_DEPTH).W))//stq的尾部索引
    

    val ldReq = Decoupled(new Req_Abter())//加载请求信号

    val data_out_mem = Input(UInt(64.W))//从储存器中读取的数据
    val data_out_stq = Input(new STQEntry())//从stq中读取的数据

    val rob_commitsignal = Input(Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))))//ROB的CommitSignal信号
  })

  //stage1地址计算
  val instr = Wire(UInt((p.XLEN - 7).W))
  val ps1_value = Wire(UInt(p.XLEN.W))
  val stage1_pipevalid = Wire(Bool())

  instr := io.ld_issue_uop.bits.instr
  ps1_value := io.ld_issue_uop.bits.ps1_value
  stage1_pipevalid := io.ld_issue_uop.valid


  val imm_temp = instr(24, 13)
  val stage1_imm = Cat(Fill(20,imm_temp(11)),imm_temp)
  val stage1_func3 = instr(7, 5)

  val stage1_ldAddr = ps1_value + stage1_imm
  val stall = Wire(Bool())


  //stage1-stage2之间的PipelineReg
  val Stage1ToStage2_ldAddr_reg = Module(new PipelineReg(p.XLEN))
  val Stage1ToStage2_func3_reg = Module(new PipelineReg(3))
  val Stage1ToStage2_pipevalid_reg = Module(new PipelineReg(1))
  val Stage1ToStage2_stqtail_reg = Module(new PipelineReg(log2Ceil(p.STQ_DEPTH)))
  val Stage1ToStage2_robidx_reg = Module(new PipelineReg(log2Ceil(p.ROB_DEPTH)))
  val Stage1ToStage2_pdst_reg  = Module(new PipelineReg(log2Ceil(p.PRF_DEPTH)))

  Stage1ToStage2_ldAddr_reg.io.data_in := stage1_ldAddr
  Stage1ToStage2_func3_reg.io.data_in := stage1_func3
  Stage1ToStage2_pipevalid_reg.io.data_in := stage1_pipevalid.asUInt
  Stage1ToStage2_stqtail_reg.io.data_in := io.ld_issue_uop.bits.stq_tail
  Stage1ToStage2_robidx_reg.io.data_in := io.ld_issue_uop.bits.rob_index
  Stage1ToStage2_pdst_reg.io.data_in := io.ld_issue_uop.bits.pdst

  Stage1ToStage2_ldAddr_reg.io.stall_in := stall
  Stage1ToStage2_func3_reg.io.stall_in  := stall
  Stage1ToStage2_pipevalid_reg.io.stall_in := stall
  Stage1ToStage2_stqtail_reg.io.stall_in := stall
  Stage1ToStage2_robidx_reg.io.stall_in := stall
  Stage1ToStage2_pdst_reg.io.stall_in := stall

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
  
  val need_flush = Wire(Bool())
  need_flush := io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
  val stage2_data_bitvalid = io.data_out_stq.bit_valid
  //为1的时候表示需要进行flush，即将传入stq的全部数取0
  val expected = MuxCase(false.B, Seq(
      (stage2_func3 === 0.U) -> (stage2_data_bitvalid(7,0) === "hFF".U),    // LB
      (stage2_func3 === 1.U) -> (stage2_data_bitvalid(15,0) === "hFFFF".U),  // LH
      (stage2_func3 === 2.U) -> (stage2_data_bitvalid(31,0) === "hFFFFFFFF".U) // LW
    ))
    //我们期望的bitvalid值，与stq传出的bitvalid值进行比较，决定后续是否需要向Abter发起访存请求

  stall := false.B
  when(!io.ldReq.ready && io.ldReq.valid){
    stall := true.B
  }

  io.addr_search_stq := stage2_ldAddr
  io.func3    :=  stage2_func3
  io.stq_tail := stage2_stqtail

  io.addr_search_stq.valid := stage2_pipevalid && (!need_flush) 
  io.func3.valid := stage2_pipevalid && (!need_flush)
  io.stq_tail.valid := stage2_pipevalid && (!need_flush)


  io.ldReq.bits.data := 0.U
  io.ldReq.bits.data_Addr := stage2_ldAddr
  io.ldReq.bits.func3 := stage2_func3
  io.ldReq.bits.write_en := false.B
  io.ldReq.valid := stage2_pipevalid && (!need_flush) && (expected)

  io.ld_issue_uop.ready := (!need_flush) && (!stall)

  val stage2_data_out_stq = io.data_out_stq.data

  //stage2-stage3之间的PipelineReg
  
  val Stage2ToStage3_data_reg = Module(new PipelineReg(p.XLEN))
  val Stage2ToStage3_bitvalid_reg = Module(new PipelineReg(p.XLEN))
  val Stage2ToStage3_pdst_reg = Module(new PipelineReg(log2Ceil(p.PRF_DEPTH)))
  val Stage2ToStage3_pipevalid_reg = Module(new PipelineReg(1))
  val Stage2ToStage3_robidx_reg = Module(new PipelineReg(log2Ceil(p.ROB_DEPTH)))
  
  Stage2ToStage3_data_reg.io.data_in := stage2_data_out_stq
  Stage2ToStage3_bitvalid_reg.io.data_in := stage2_data_bitvalid
  Stage2ToStage3_pdst_reg.io.data_in := stage2_pdst
  Stage2ToStage3_pipevalid_reg.io.data_in := stage2_pipevalid.asUInt
  Stage2ToStage3_robidx_reg.io.data_in := stage2_robidx

  Stage2ToStage3_data_reg.io.stall_in := stall
  Stage2ToStage3_bitvalid_reg.io.stall_in := stall
  Stage2ToStage3_pdst_reg.io.stall_in := stall
  Stage2ToStage3_pipevalid_reg.io.stall_in := stall
  Stage2ToStage3_robidx_reg.io.stall_in := stall

  Stage2ToStage3_data_reg.io.reset := need_flush
  Stage2ToStage3_bitvalid_reg.io.reset := need_flush
  Stage2ToStage3_pdst_reg.io.reset := need_flush
  Stage2ToStage3_pipevalid_reg.io.reset := need_flush
  Stage2ToStage3_robidx_reg.io.reset := need_flush
  

//stage3进行mem和stq的数据合并
  val stage3_data = Stage2ToStage3_data_reg.io.data_out
  val stage3_bitvalid = Stage2ToStage3_bitvalid_reg.io.data_out
  
  val data_out_mem = Wire(UInt(p.XLEN.W))
  data_out_mem := io.data_out_mem
  val final_data = (stage3_data & stage3_bitvalid) | (data_out_mem & (~stage3_bitvalid).asUInt)

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

  
}


class StorePipeline(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val st_issue_uop = Flipped(Valid(new STISSUE_STPIPE_uop()))//存储指令的uop
    val stu_wb_uop = Valid((new STPIPE_WB_uop()))//存储完成的信号,wb to ROB

    val data_into_stq = UInt(p.XLEN.W)//需要写入stq的数据
    val dataAddr_into_stq = Valid(UInt(p.XLEN.W))//需要写入stq的地址
    val func3 = UInt(3.W)//fun3信号
    val stq_index = UInt(log2Ceil(p.STQ_DEPTH).W)//需要写入stq的索引

    val rob_commitsignal = Input(Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))))//ROB的CommitSignal信号
  })  

//stage1地址计算
  val instr = Wire(UInt((p.XLEN - 7).W))
  val ps1_value = Wire(UInt(p.XLEN.W))

  instr := io.st_issue_uop.bits.instr
  ps1_value := io.st_issue_uop.bits.ps1_value
  
  val imm_high = instr(24, 18)
  val imm_low = instr(4, 0)
  val stage1_func3 = instr(7, 5)

  val stage1_imm = Cat(Fill(20,imm_high(6)),imm_high, imm_low)

  val stage1_dataAddr = ps1_value + stage1_imm

  val pipeline_valid = Wire(Bool())
  pipeline_valid := io.st_issue_uop.valid
  


//下面都是PipelineReg的操作，stage1-stage2之间的pipelinereg
  val Stage1ToStage2_data_reg = Module(new PipelineReg(p.XLEN))
  val Stage1ToStage2_addr_reg = Module(new PipelineReg(p.XLEN))
  val Stage1ToStage2_stqidx_reg = Module(new PipelineReg(log2Ceil(p.STQ_DEPTH)))
  val Stage1ToStage2_robidx_reg = Module(new PipelineReg(log2Ceil(p.ROB_DEPTH)))
  val Stage1ToStage2_func3_reg = Module(new PipelineReg(3))
  val Stage1ToStage2_valid_reg = Module(new PipelineReg(1))
  
  Stage1ToStage2_data_reg.io.data_in := io.st_issue_uop.bits.ps2_value
  Stage1ToStage2_addr_reg.io.data_in := stage1_dataAddr
  Stage1ToStage2_stqidx_reg.io.data_in := io.st_issue_uop.bits.stq_index
  Stage1ToStage2_robidx_reg.io.data_in := io.st_issue_uop.bits.rob_index
  Stage1ToStage2_valid_reg.io.data_in  := io.st_issue_uop.valid.asUInt
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

  val need_flush = io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
  //为1的时候表示需要进行flush，即将传入stq的全部数取0

  io.data_into_stq     := stage2_data 
  io.dataAddr_into_stq := stage2_addr
  io.stq_index         := stage2_stqidx
  io.func3             := stage2_func3

  
  io.dataAddr_into_stq.valid := !need_flush && Stage1ToStage2_valid_reg.io.data_out.asBool


  io.stu_wb_uop.valid  := Stage1ToStage2_valid_reg.io.data_out.asBool && (!need_flush)
  //仅当st_issue_uop传入有效且不需要flush时wb rob的uop才有效
  io.stu_wb_uop.bits.rob_index := Stage1ToStage2_robidx_reg.io.data_out


}
//stq模块

class STQEntry(implicit p: Parameters) extends Bundle {
  val data = UInt(p.XLEN.W)
  val data_Addr = UInt(p.XLEN.W)
  val bit_valid = UInt(p.XLEN.W)
  val func3 = UInt(3.W)
}

class StoreQueue(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val stq_full = Output(Bool())//stq是否为满,1表示满
    val stq_tail = Output(UInt(log2Ceil(p.STQ_DEPTH).W))//stq的尾部索引 
    val stq_head = Output(UInt(log2Ceil(p.STQ_DEPTH).W))//stq的头部索引

    val input_tail = Input(UInt(log2Ceil(p.STQ_DEPTH).W))//输入的tail指针，用于后续的查找
    val addr_search_stq = Input(UInt(p.XLEN.W))//地址搜索信号,进入stq的搜索地址
    val ld_func3 = Input(UInt(3.W))//fun3信号
    val searched_data = Output(new STQEntry)//从stq中读取的数据
    val stqReq = Decoupled(new Req_Abter())//存储请求信号

    val st_dis = Input(Vec(p.CORE_WIDTH, Bool()))//用于更新store queue（在lsu中）的tail（full标志位）
    val rob_commitsignal = Input(Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))))//ROB的CommitSignal信号

    val dataAddr_into_stq = Flipped(Valid(UInt(p.XLEN.W)))//需要写入stq的地址
    val data_into_stq = Input(UInt(p.XLEN.W))//需要写入stq的数据
    val stq_index = Input(UInt(log2Ceil(p.STQ_DEPTH).W))//需要写入stq的索引
    val st_func3 = Input(UInt(3.W))//fun3信号
  })   

  //flush信号，作为stq的reset信号
  //当rob的commit信号为valid且mispred为1时，表示需要flush
  val need_flush = Wire(Bool())
  need_flush := io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred
  //初始化stq的entries
  val stq_entries = withReset(need_flush){
      RegInit(VecInit(Seq.fill(p.STQ_DEPTH){
      (new STQEntry).Lit(
        _.data -> 0.U,
        _.data_Addr -> 0.U,
        _.bit_valid -> 0.U,
        _.func3 -> 0.U
      )
      }))
  }


//stq的head和tail指针
  val head = withReset(need_flush){
    RegInit(0.U(log2Ceil(p.STQ_DEPTH).W))
  }
  val tail = withReset(need_flush){
    RegInit(0.U(log2Ceil(p.STQ_DEPTH).W))
  }

  //更新指针位置
  def nextPtr(ptr: UInt, inc: UInt): UInt = {
    val next = ptr + inc
    Mux(next >= p.STQ_DEPTH.U, next - p.STQ_DEPTH.U, next)
  }
  val tail_inc = Mux(io.st_dis(1), 2.U, Mux(io.st_dis(0), 1.U, 0.U))
  val tail_next = nextPtr(tail, tail_inc)
  
  when(!need_flush) {
    //发送向Arbiter的信号
    val head_entry = stq_entries(head)
    val head_valid = MuxCase(false.B, Seq(
      (head_entry.func3 === 0.U) -> (head_entry.bit_valid(7,0) === "hFF".U),    // SB
      (head_entry.func3 === 1.U) -> (head_entry.bit_valid(15,0) === "hFFFF".U),  // SH
      (head_entry.func3 === 2.U) -> (head_entry.bit_valid(31,0) === "hFFFFFFFF".U) // SW
    ))

    io.stqReq.valid := head_valid
    io.stqReq.bits.data  := head_entry.data
    io.stqReq.bits.data_Addr := head_entry.data_Addr
    io.stqReq.bits.func3 := head_entry.func3
    io.stqReq.bits.write_en  := true.B

    when(io.stqReq.ready && io.stqReq.valid) {
      head := nextPtr(head, 1.U)
      stq_entries(head).bit_valid := 0.U
    }

  //tail的移动
    when(tail_inc =/= 0.U) {
      tail := tail_next
    }

  //写入stq的操作
    when(io.dataAddr_into_stq.valid){
      val stq_wb_idx = Wire(UInt(log2Ceil(p.STQ_DEPTH).W))
      stq_wb_idx := io.stq_index
      stq_entries(stq_wb_idx).data := io.data_into_stq
      stq_entries(stq_wb_idx).data_Addr := io.dataAddr_into_stq
      stq_entries(stq_wb_idx).func3 := io.st_func3
      stq_entries(stq_wb_idx).bit_valid := MuxCase(0.U, Seq(
        (io.st_func3 === 0.U) -> ("hFF".U),    // SB
        (io.st_func3 === 1.U) -> ("hFFFF".U),  // SH
        (io.st_func3 === 2.U) -> ("hFFFFFFFF".U) // SW
    ))
    }
    
  }

  val stq_full = tail_next === head

  io.stq_full := stq_full
  io.stq_tail := tail
  io.stq_head := head


  //stq的搜索操作
  def isInrange(idx: UInt,head: UInt, tail: UInt): Bool = {
    Mux(tail >= head, idx >= head && idx < tail, idx >= head || idx < tail)
  }
  
  val bit_width = Mux(io.ld_func3 === 0.U, 8.U, 
                      Mux(io.ld_func3 === 1.U, 16.U,
                         Mux(io.ld_func3 === 2.U, 32.U, 0.U)))
  val bytewidth = (bit_width >> 3).asUInt

  val SearchAddr = Wire(UInt(p.XLEN.W))
  val data_res = Wire(UInt(p.XLEN.W))
  val data_bit_valid = Wire(UInt(p.XLEN.W))
  val entry_bytevalid = Wire(Vec(p.STQ_DEPTH, Vec(4, Bool())))

  for(i <- 0 until p.STQ_DEPTH){
    for(j <- 0 until 4) {
      val byte_bits = stq_entries(i).bit_valid(8 * j + 7, 8 * j)
      entry_bytevalid(i)(j) := (byte_bits.andR).asUInt
    }
  }

  val byteSearched = WireDefault(Vec(4, Vec(p.STQ_DEPTH,Bool())), 0.U.asTypeOf(Vec(4, Vec(p.STQ_DEPTH,Bool()))))
  val found_data = WireDefault(Vec(4,UInt(8.W)), 0.U.asTypeOf(Vec(4,UInt(8.W))))
  val found_data_bytevalid = WireDefault(Vec(4,Bool()), 0.U.asTypeOf(Vec(4,Bool())))


  data_res := 0.U
  data_bit_valid := 0.U
  val mask = Wire(UInt(4.W))
  switch(io.ld_func3){
    is(0.U){
      mask := "b1000".U
    }
    is(1.U){
      mask := "b1100".U
    }
    is(2.U){
      mask := "b1111".U
    }
  }

  when(!need_flush){
    for(delta_byte <- 0 until 4){
      val curr_addr = SearchAddr + delta_byte.U
      for(i <- 0 until p.STQ_DEPTH){
        val entry = stq_entries(i)
        val store_byte = Mux(entry.func3 === 0.U, 1.U,
          Mux(entry.func3 === 1.U, 2.U,
            Mux(entry.func3 === 2.U, 4.U, 0.U)))
        byteSearched(delta_byte)(i) := (isInrange(i.U,head, io.input_tail)) &&
          (curr_addr >= entry.data_Addr) && (curr_addr < (entry.data_Addr + store_byte)) && mask(delta_byte)
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
      found_data(delta_byte) := (entry.data >> offset)(7, 0)
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
  io.searched_data.data_Addr := io.dataAddr_into_stq
  io.searched_data.func3 := io.ld_func3
  io.searched_data.bit_valid := found_data_bitvalid

}
//LSU的模块定义，目前只完成了IO接口的定义，内部逻辑还未完成
class LSU(implicit p: Parameters) extends Module {

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


  //连接加载管线模块的信号
    load_pipeline.io.ld_issue_uop <> io.ld_issue_uop//将加载指令的uop连接到加载管线模块的输入端口
    load_pipeline.io.data_out_mem := io.data_out_mem(31,0)//将从存储器中读取的数据连接到加载管线模块的输入端口
    load_pipeline.io.addr_search_stq <> store_queue.io.addr_search_stq//将地址搜索信号连接到加载管线模块的输入端口
    load_pipeline.io.func3 <> store_queue.io.ld_func3
    load_pipeline.io.stq_tail <> store_queue.io.input_tail
    load_pipeline.io.data_out_stq <> store_queue.io.searched_data
    load_pipeline.io.rob_commitsignal := io.rob_commitsignal
    load_pipeline.io.ldu_wb_uop <> io.ldu_wb_uop

  //连接storepipeline的信号
    store_pipeline.io.st_issue_uop := io.st_issue_uop
    io.stu_wb_uop := store_pipeline.io.stu_wb_uop
    store_pipeline.io.data_into_stq <> store_queue.io.data_into_stq
    store_pipeline.io.dataAddr_into_stq <> store_queue.io.dataAddr_into_stq
    store_pipeline.io.func3 <> store_queue.io.st_func3
    store_pipeline.io.stq_index <> store_queue.io.stq_index
    store_pipeline.io.rob_commitsignal := io.rob_commitsignal

  //连接STQ的信号
    store_queue.io.st_dis := io.st_dis
    io.stq_head := store_queue.io.stq_head
    io.stq_tail := store_queue.io.stq_tail
    io.stq_full :=store_queue.io.stq_tail



}
