package rsd_rv32.execution

import chisel3._
import chisel3.util._
import rsd_rv32.scheduler._
import rsd_rv32.common._

//该Bundle用于后续向Arbiter传输信号,不是interface
class Req_Abter(implicit p: Parameters) extends CustomBundle {

  val func3     = UInt(3.W) //访存指令的fun3字段
  val data      = UInt(64.W)
  val data_Addr = UInt(64.W)
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
  val stq_tail  = Output(UInt(log2Ceil(p.STQ_Depth).W))//stq的尾部索引 
  val stq_head  = Output(UInt(log2Ceil(p.STQ_Depth).W))//stq的头部索引
  val stq_full  = Output(Bool())//stq是否为满,1表示满
  val st_dis = Input(Vec(p.CORE_WIDTH, Bool()))//存储指令被派遣的情况(00表示没有，01表示派遣一条，11表示派遣两条)，用于更新store queue（在lsu中）的tail（full标志位）
  
  //with ROB
  val rob_commitsignal = Input(Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))))//ROB的CommitSignal信号
  val stu_wb_uop = Valid((new STPIPE_WB_uop()))//存储完成的信号,wb to ROB
  val ldu_wb_uop  = Valid((new ALU_WB_uop()))//加载完成的信号,wb to ROB and PRF
}

class PipelineReg(val width: Int) extends Module {
  val io = IO(new Bundle {
    val stall_in = Input(Bool())
    val data_in  = Input(UInt(width.W))
    val data_out = Output(UInt(width.W))
  })
  val reg = RegInit(1.U(width.W))

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
    val addr_search_stq = Output(UInt(XLEN.W))//地址搜索信号,进入stq的搜索地址
    val ldReq = Decoupled(new Req_Abter())//加载请求信号
    val data_out_mem = Input(UInt(64.W))//从储存器中读取的数据
  })

}


class StorePipeline(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val st_issue_uop = Flipped(Valid(new STISSUE_STPIPE_uop()))//存储指令的uop
    val stu_wb_uop = Valid((new STPIPE_WB_uop()))//存储完成的信号,wb to ROB
    val data_into_stq = Output(UInt(XLEN.W))//需要写入stq的数据
    val dataAddr_into_stq = Output(UInt(XLEN.W))//需要写入stq的地址
    val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent())))//ROB的CommitSignal信号
  })
}
//stq模块



class StoreQueue(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val stq_full = Output(Bool())//stq是否为满,1表示满
    val stq_tail = Output(UInt(log2Ceil(p.STQ_Depth).W))//stq的尾部索引 
    val stq_head = Output(UInt(log2Ceil(p.STQ_Depth).W))//stq的头部索引
    val addr_search_stq = Input(UInt(XLEN.W))//地址搜索信号,进入stq的搜索地址
    val stqReq = Decoupled(new Req_Abter())//存储请求信号
    val stq_dis = Input(Vec(p.CORE_WIDTH, Bool()))//用于更新store queue（在lsu中）的tail（full标志位）
    val rob_commitsignal = Input(Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent()))))//ROB的CommitSignal信号
  })


}

//LSU的模块定义，目前只完成了IO接口的定义，内部逻辑还未完成
class LSU(implicit p: Parameters) extends Module {

    val io = IO(new LSUIO())//定义LSU的IO接口

    //内部信号定义还未完成，待定
}
