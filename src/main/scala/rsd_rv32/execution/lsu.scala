package rsd_rv32.execution

import chisel3._
import chisel3.util._
import rsd_rv32.scheduler._
import rsd_rv32.common._

//该Bundle用于后续向Arbiter传输信号,不是interface
class Req_Abter(implicit p: Parameters) extends Bundle {

  val func3     = UInt(3.W) //访存指令的fun3字段
  val data      = UInt(64.W)
  val data_Addr = UInt(64.W)
  val write_en  = Bool()

}

class LSUIO(implicit p: Parameters) extends Bundle {
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
  val stq_tail  = Output(log2Ceil(p.STQ_Depth).W)//stq的尾部索引 
  val stq_head  = Output(log2Ceil(p.STQ_Depth).W)//stq的头部索引
  val stq_full  = Output(Bool())//stq是否为满,1表示满
  val st_dis = Input(Vec(p.CORE_WIDTH, Bool()))//存储指令被派遣的情况(00表示没有，01表示派遣一条，11表示派遣两条)，用于更新store queue（在lsu中）的tail（full标志位）
  
  //with ROB
  val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent())))//ROB的CommitSignal信号
  val stu_wb_uop = Valid((new STPIPE_WB_uop()))//存储完成的信号,wb to ROB
  val ldu_wb_uop  = Valid((new ALU_WB_uop()))//加载完成的信号,wb to ROB and PRF
}

//LSU的模块定义，目前只完成了IO接口的定义，内部逻辑还未完成
class LSU(implicit p: Parameters) extends Module {

    val io = IO(new LSUIO())//定义LSU的IO接口

    //内部信号定义还未完成，待定
}
