package rsd_rv32.execution

import chisel3._
import chisel3.util._
import rsd_rv32.scheduler._
import rsd_rv32.common._

class DCacheRequest(implicit p:Parameters) extends Bundle()(p) 
with HasUOP {
  val addr = UInt(p.CoreMaxAddrbits.W)
  val data = Bits(p.CoreDataBits.W)
}

class DCacheResponse(implicit p:Parameters) extends Bundle()(p)
with HasUOP {
  val addr = UInt(p.CoreMaxAddrbits.W)
  val data = Bits(p.CoreDataBits.W)
}


//描述与数据内容交互的各种信号，用于管理L/S请求和缓存访问，包含一部分异常处理、顺序控制的功能
class LSUMemIO(implicit p: Parameters , edge: TLEdgeOut) extends Bundle()(p) {
  val req           = new DecoupledIO(p.lsuWidth,Valid(new DCacheRequest))//LSU发出的数据缓存请求
  val resp          = new Flipped(p.lsuWidth,Valid(new DCacheResponse))//LSU接收数据缓存响应

  val req_kill      = Output(p.lsuWidth,Bool())//表示每个req是否被kill
  val req_nack_adv  = Input(p.lsuWidth,Bool())//表示某个req是否被拒绝
  val store_ack     = Flipped(Vec(p.lsuWidth,new ValidIO(new DCacheRequest)))//存储请求的确认
  val nack          = Flipped(Vec(p.lsuWidth,new ValidIO(new DCacheRequest)))//作为接口接受neck
  val load_rel_resp = Flipped(new DecoupledIO(new DCacheResponse))//作为接口接受缓存的load/release响应
  val bradate       = Output(new bradateInfo)//报告分支更新信息
  val exception     = Output(Bool())//输出异常

  val rob_pnr_idx   = Output(UInt((p.robAddrSz).W))
  val rob_head_idx  = Output(UInt((p.robAddrSz).W))//rob中的后备和头部索引

  val release       = Flipped(new DecoupledIO(new TLBundle(edge.bundle)))//处理缓存协议的释放操作

  val force_order   = Output(Bool())//强制顺序控制，在保证l/s顺序的时候激活
  val order         = Input(Bool())//顺序控制信号，表示当前是否满足顺序要求

}

//LSU的核心IO接口，与execution、dcache、rob等进行交互


class LSU_Issue_IO(implicit p: Parameters) extends Bundle()(p){

  val store_issue_uop = Flipped(Decoupled(new uop()))
  val load_issue_uop  = Flipped(Decoupled(new uop()))
}

class STQ_Dispatch_IO(implicit p: Parameters) extends Bundle()(p){

  val stq_tail_idx  = Output(log2Ceil(p.STQ_Depth).W)//stq的尾部索引 
  val stq_head_idx  = Output(log2Ceil(p.STQ_Depth).W)//stq的头部索引
  val store_dis_uop = Valid(Vec(p.DISPATCH_WIDTH,new uop()))//存储指令的uop
  
}

class LSU_ROB_IO(implicit p: Parameters) extends Bundle()(p){

  val store_signal = Input(Vec(p.DISPATCH_WIDTH, 
  UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W)))

}

class LSU_Broadcast(implicit p: Parameters) extends Bundle()(p){

  val store_finish = Valid(new uop())//存储完成的信号
  val load_finish  = Valid(new uop())//加载完成的信号

}

class LSUIO(implicit p: Parameters,edge : TLEdgeOut) extends Bundle()(p){
  
  val lsu_broadcast_commit = new LSU_Broadcast//LSU的提交信号
  val lsu_rob       = new LSU_ROB_IO//LSU与ROB的交互信号
  val lsu_issue     = new LSU_Issue_IO//LSU与issue的交互信号
  val stq_dispatch  = new STQ_Dispatch_IO//LSU与stq的交互信号
  
}

//LSU的模块定义，目前只完成了IO接口的定义，内部逻辑还未完成
class LSU(implicit p: Parameters,edge : TLEdgeOut) extends Module()(p){

    val io = IO(new LSUIO())//定义LSU的IO接口

    //内部信号定义还未完成，待定
}