package rsd_rv32.execution

import chisel3._
import chisel3.util._
import rsd_rv32.scheduler._
import rsd_rv32.common._

//该Bundle用于后续向Arbiter传输信号
class Req_Abter(implicit p: Parameters) extends Bundle()(p){

  val data      = UInt(64.w)
  val data_Addr = UInt(64.w)
  val write_en  = Bool()

}


//与MEM的IO接口
class LSU_MEM_IO(implicit p: Parameters) extends Bundle()(p){

  val data_addr     = Output(UInt(p.XLEN.W))//访存指令的目标地址
  val data_into_mem = Output(UInt(p.XLEN))//需要写入储存器的数据
  val write_en      = Output(Bool())//写使能信号

  val func3 = Output(UInt(3.W))//访存指令的fun3字段

  val data_out_mem  = Input(UInt(p.XLEN.W))//从储存器中读取的数据
}

//与Issue的IO接口，主要用于接收来自Issue的load和store指令，并将其传递给LSU的其他模块进行处理
class LSU_Issue_IO(implicit p: Parameters) extends Bundle()(p){

  val store_issue_uop = Flipped(Decoupled(new STISSUE_STPIPE_uop()))//存储指令的uop
  
  val load_issue_uop  = Flipped(Decoupled(new LDISSUE_LDPIPE_uop()))//加载指令的uop
  
  //接收来自issue的load和store指令
}

//向dispatcher发送STQ的头尾指针，方便后续store，load指令的调度
//STQ的头尾指针主要用于存储指令的调度和执行
class STQ_Dispatcher_IO(implicit p: Parameters) extends Bundle()(p){

  val stq_tail_ptx  = Output(log2Ceil(p.STQ_Depth).W)//stq的尾部索引 
  val stq_head_ptx  = Output(log2Ceil(p.STQ_Depth).W)//stq的头部索引
 
  val stq_empty     = Output(Bool())//stq是否为空
  val dis_store     = Input(Vec(2,Bool()))//存储指令是否被调度

}

//接收来自ROB的CommitSignal信号，用于执行后续入STQ的操作
class LSU_ROB_IO(implicit p: Parameters) extends Bundle()(p){

  val store_signal = Input(Vec(p.DISPATCH_WIDTH, 
  UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W)))

}

//广播信号，store完成信号主要是给ROB使用
//load完成信号则提供给PRF跟ROB
class LSU_SL_Commit(implicit p: Parameters) extends Bundle()(p){

  val store_finish = Valid((new STPIPE_WB_uop()))//存储完成的信号,wb to ROB
  val load_finish  = Valid((new LDPIPE_WB_uop()))//加载完成的信号,wb to ROB and PRF

}

class LSUIO(implicit p: Parameters) extends Bundle()(p){
  
  val lsu_mem       = new LSU_MEM_IO//LSU与MEM的交互信号
  val lsu_sl_finish = new LSU_SL_Commit//LSU的在完成store或者load后的写回信号
  val lsu_rob       = new LSU_ROB_IO//接收后续ROB的Commit_signal信号
  val lsu_issue     = new LSU_Issue_IO//LSU与issue的交互信号
  val stq_dispatch  = new STQ_Dispatch_IO//LSU与stq的交互信号
  
}

//LSU的模块定义，目前只完成了IO接口的定义，内部逻辑还未完成
class LSU(implicit p: Parameters) extends Module()(p){

    val io = IO(new LSUIO())//定义LSU的IO接口

    //内部信号定义还未完成，待定
}