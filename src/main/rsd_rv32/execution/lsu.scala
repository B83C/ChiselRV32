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

  val perf = Input(new Bundle {
    val acquire     = Bool()
    val release     = Bool()
  })
  //指示信号的获取和释放，进行性能的监控
}

//LSU的核心IO接口，与execution、dcache、rob等进行交互
class LSUCoreIO(implicit p: Parameters) extends Bundle()(p) {
  //这里的MenGen定义为
  //class MemGen(implicit p: Parameters) extends BoomBundle
  // with HasBoomUOP
  //{
  //val data = UInt(xLen.W)
  //}
    val agen        = Flipped(Vec(p.lsuWidth, Valid(new MemGen)))//地址生成器的输入
    val dgen        = Flipped(Vec(p.memWidth + 1, Valid(new MemGen)))//数据生成器的输入,memWidth为内存宽度
  //class Wakeup(implicit p: Parameters) extends BoomBundle
  //with HasBoomUOP
  //{
  //val bypassable = Bool()
  //val speculative_mask = UInt(aluWidth.W)
  //val rebusy = Bool()
  //}
    val iwakeups    = Vec(p.lsuWidth, Valid(new Wakeup))//LSU的唤醒信号
    val iresp       = Vec(p.lsuWidth, Valid(new ExeUnitResp(xLen)))
    val fresp       = Vec(p.lsuWidth, Valid(new ExeUnitResp(xLen)))
    //执行单元返回的响应数据
    val sfence      = Flipped(Valid(new rocket.SFenceReq))//传递sfence的请求，用于内容屏蔽

    val dis_uops    = Vec(p.coreWidth, Valid(new MicroOp))//解码后的微操作
    //coreWidth表示时钟周期的指令数量

    val dis_ldq_idx = Output(Vec(p.coreWidth,UInt((1+ldpAddrSz).W)))
    val dis_stq_idx = Output(Vec(p.coreWidth,UInt((1+stqAddrSz).W)))
    //LDQ和STQ索引,用于跟踪指令的队列位置

    val ldq_full    = Output(Vec(p.coreWidth,Bool()))
    val stq_full    = Output(Vec(p.coreWidth,Bool()))
    //表示LDQ和STQ是否满了的信号 

    val commit      = Input(new CommitInfo)//提交信号 CommitInfo是一个Bundle，包含了提交的指令信息
    val commitLoadAtRobHead = Input(Bool())//当前是否在rob的头部进行加载

    val clr_busy_bit= Output(Bool())//清除busybit的信号
    val clr_unsafe  = Output(Bool())//清除不安全的加载状态

    val fence_dmem  = Input(Bool())//控制内存屏障操作
    val bradate     = Input(new bradateInfo)//分支更新信息

    val rob_head_idx= Input(UInt((p.robAddrSz).W))//rob的头部索引
    val rob_pnr_idx = Input(UInt((p.robAddrSz).W))//rob的后备索引
    
    val exception   = Input(Bool())//异常信号

    val Fencei_ready= Output(Bool())//表示FENCEI是否准备好
    val load_excep  = Output(Valid(new Exception))//表示加载异常的信号

    val tsc_reg     = Input(UInt())//时间戳寄存器(计数器)的值

    val status      = Input(new rocket.MStatus)//接收机器模式状态寄存器的值

    val bp          = Input(Vec(breakpoint_num,new rocket.BP))//接收断点设置
    val mach_context= Input(UInt)//接收机器模式上下文的值
    val s_context   = Input(UInt)//接收超线程模式上下文的值

    val perf = Output(new Bundle {
      val acquire = Bool()
      val release = Bool()
      val tlbMiss = Bool()//tlb可能不需要，此处待定
    })
    //输出信号，用于性能监控
}

//TLE部分可能不需要做，待定

//提供LSU的核心、内存（TLB）的IO接口
class LSUIO(implicit p: Parameters,edge : TLEdgeOut) extends Bundle()(p){
  val ptw   = new rocket.TLBPTWIO//TLB的输入输出接口,待定
  val core  = new LSUCoreIO//LSU的核心接口
  val dcache_mem = new LSUMemIO//LSU的内存接口
}