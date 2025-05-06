package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import chisel3.experimental._

import rsd_rv32.common._


// 重命名单元将逻辑寄存器地址映射成实际寄存器。逻辑寄存器指的是ISA定义的x0-x31，而实际寄存器数量多于32个，一般可达128个。主要解决WAW，WAR等问题。
class RenameUnit_IO(implicit p: Parameters) extends Bundle {
  //with ID
  val id_uop = Vec(p.CORE_WIDTH, Flipped(Valid(new ID_RENAME_uop()))) //来自ID单元的uop
  val rename_ready = Output(Bool()) // 反馈给IDU，显示Rename单元是否准备好接收指令
  //with ROB
  val rob_commitsignal = Vec(p.CORE_WIDTH, Flipped(Valid(new ROBContent())))  //ROB提交时的广播信号，rob正常提交指令时更新amt与rmt，发生误预测时对本模块进行恢复
  //with Dispatch
  val dis_uop = Vec(p.CORE_WIDTH, Valid(new RENAME_DISPATCH_uop())) //发往Dispatch单元的uop
  val dis_ready = Input(Bool()) // 来自Dispatch单元的反馈，显示dispatch单元是否准备好接收指令
}

/*
  重命名单元采用显示重命名方式消除WAW、WAR，并保留着真实数据依赖RAW。主要维护preg_freelist，RenameMapTable(RMT)和ArchitecturMapTable。其中preg_freelist为FIFO，具有（PRF_DEPTH - 32）个表项。
推测状态重命名表RMT，从上到下依次存储x0～x31所映射到物理寄存器地址。AMT从上到下存储x0～x31所映射的处于架构状态（非推测状态）物理寄存器。
  当ID单元送来指令uop后，若含有源操作数，则从RMT中读出源操作数映射的物理寄存器地址；若含有目的寄存器，则从preg_freelist的head处读出空闲状态的物理寄存器地址，而后更新RMT；
  当指令退休时，根据rob存储的rd条目索引AMT，并把对应物理寄存器地址改为提交的物理寄存器地址，并把旧的物理寄存器地址放入preg_freelist中（freelist的tail + 1 即可）。
  Rename单元通过ROB队首的广播信息来判断是否发生误预测，换言之，误预测指令到ROB队首后才会被处理，因而RMT到恢复只需将AMT复制到RMT，并冲刷掉该模块正在处理的指令。
*/
class RenameUnit(implicit p: Parameters) extends Module {
  val io = IO(new RenameUnit_IO())

  val rmt = RegInit(VecInit(Seq.fill(32)(0.U(log2Ceil(p.PRF_DEPTH).W)))) //重命名表，存储逻辑寄存器到物理寄存器的映射关系
  val rmt_valid = RegInit(VecInit(Seq.fill(32)(false.B))) //重命名表有效位
  val amt = RegInit(VecInit((0 to 31).map(i => i.U(log2Ceil(p.PRF_DEPTH).W)))) //架构寄存器表，存储逻辑寄存器到物理寄存器的映射关系

  val freelist = RegInit(VecInit((32 to p.PRF_DEPTH - 1).map(i => i.U(log2Ceil(p.PRF_DEPTH).W)))) //空闲寄存器列表，存储空闲物理寄存器的地址
  val freelist_head = RegInit(0.U(log2Ceil(p.PRF_DEPTH - 32).W)) //空闲寄存器列表头指针
  val freelist_tail = RegInit(0.U(log2Ceil(p.PRF_DEPTH - 32).W)) //空闲寄存器列表尾指针
  val freelist_empty = RegInit(false.B) //空闲寄存器列表空标志

  val rename_ready = WireDefault(true.B) //重命名单元准备好接收指令标志
  val dis_uop = WireDefault(VecInit(Seq.fill(p.CORE_WIDTH)(0.U.asTypeOf(Valid(new RENAME_DISPATCH_uop()))))) //发往Dispatch单元的uop
  val head_next = WireDefault(freelist_head)
  val tail_next = WireDefault(freelist_tail)
  val empty_next = WireDefault(false.B)
  val reg_dis_uop = RegEnable(next = dis_uop, enable = io.dis_ready, init = VecInit(Seq.fill(p.CORE_WIDTH)(0.U.asTypeOf(Valid(new RENAME_DISPATCH_uop()))))) //寄存器存储发往Dispatch单元的uop

  io.rename_ready := rename_ready //反馈给ID单元
  io.dis_uop := reg_dis_uop //发往Dispatch单元的uop
  freelist_head := head_next //更新空闲寄存器列表头指针
  freelist_tail := tail_next //更新空闲寄存器列表尾指针

  val flush = Wire(Bool()) //重命名单元冲刷标志
  flush := io.rob_commitsignal(0).valid && io.rob_commitsignal(0).bits.mispred //发生误预测时冲刷重命名单元

  val valid_bits = Wire(UInt(2.W))
  valid_bits := io.id_uop(0).valid ## io.id_uop(1).valid //ID单元的指令有效位

  val rob_valid_bits = Wire(UInt(2.W))
  rob_valid_bits := io.rob_commitsignal(0).valid ## io.rob_commitsignal(1).valid //ROB单元的指令有效位

  def needPd(instr_type : InstrType.Type) : Bool = {
    instr_type === InstrType.ALU || instr_type === InstrType.Jump || instr_type === InstrType.LD || instr_type === InstrType.CSR || instr_type === InstrType.MUL || instr_type === InstrType.DIV_REM
  }

  when(flush){
    head_next := freelist_tail
    for(i <- 0 until 32){
      rmt_valid(i) := false.B
    }
    freelist_empty := false.B
  }

  when(!flush){
    switch(valid_bits){
      is("b10".U){
        switch(io.id_uop(0).bits.instr_type){
          is(InstrType.Branch, InstrType.ST){
            rename_ready := io.dis_ready

            dis_uop(0).valid := true.B
            dis_uop(0).bits.instr := io.id_uop(0).bits.instr
            dis_uop(0).bits.instr_type := io.id_uop(0).bits.instr_type
            dis_uop(0).bits.fu_signals := io.id_uop(0).bits.fu_signals
            dis_uop(0).bits.instr_addr := io.id_uop(0).bits.instr_addr
            dis_uop(0).bits.target_PC := io.id_uop(0).bits.target_PC
            dis_uop(0).bits.GHR := io.id_uop(0).bits.GHR
            dis_uop(0).bits.branch_pred := io.id_uop(0).bits.branch_pred
            dis_uop(0).bits.btb_hit := io.id_uop(0).bits.btb_hit
          }
          is(InstrType.ALU, InstrType.Jump, InstrType.LD, InstrType.CSR, InstrType.MUL, InstrType.DIV_REM){
            rename_ready := io.dis_ready && (freelist_head =/= freelist_tail || !freelist_empty)

            when(rename_ready){
              dis_uop(0).valid := true.B
              dis_uop(0).bits.instr := io.id_uop(0).bits.instr
              dis_uop(0).bits.instr_type := io.id_uop(0).bits.instr_type
              dis_uop(0).bits.fu_signals := io.id_uop(0).bits.fu_signals
              dis_uop(0).bits.instr_addr := io.id_uop(0).bits.instr_addr
              dis_uop(0).bits.target_PC := io.id_uop(0).bits.target_PC
              dis_uop(0).bits.GHR := io.id_uop(0).bits.GHR
              dis_uop(0).bits.branch_pred := io.id_uop(0).bits.branch_pred
              dis_uop(0).bits.btb_hit := io.id_uop(0).bits.btb_hit
          
              dis_uop(0).bits.pdst := freelist(freelist_head) //从空闲寄存器列表中读出空闲物理寄存器地址

              head_next := Mux(freelist_head =/= (p.PRF_DEPTH - 32 - 1).U, freelist_head + 1.U, 0.U)
              /*when(freelist_head + 1.U === freelist_tail){
                freelist_empty := true.B //空闲寄存器列表空
              }*/
              empty_next := head_next === freelist_tail

              rmt(io.id_uop(0).bits.instr(4,0)) := freelist(freelist_head) //更新重命名表
              rmt_valid(io.id_uop(0).bits.instr(4,0)) := true.B //更新重命名表有效位
            }
          }
        }
        dis_uop(0).bits.ps1 := Mux(rmt_valid(io.id_uop(0).bits.instr(12,8)), rmt(io.id_uop(0).bits.instr(12,8)), amt(io.id_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
        dis_uop(0).bits.ps2 := Mux(rmt_valid(io.id_uop(0).bits.instr(17,13)), rmt(io.id_uop(0).bits.instr(17,13)), amt(io.id_uop(0).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
      }
      is("b11".U){
        when(io.id_uop(0).instr_type.needPd){
          when(io.id_uop(1).instr_type.needPd){
            rename_ready := io.dis_ready && (freelist_tail - freelist_head >= 2.U || (freelist_head === freelist_tail && !freelist_empty))
            when(rename_ready){
              dis_uop(0).valid := true.B
              dis_uop(0).bits.instr := io.id_uop(0).bits.instr
              dis_uop(0).bits.instr_type := io.id_uop(0).bits.instr_type
              dis_uop(0).bits.fu_signals := io.id_uop(0).bits.fu_signals
              dis_uop(0).bits.instr_addr := io.id_uop(0).bits.instr_addr
              dis_uop(0).bits.target_PC := io.id_uop(0).bits.target_PC
              dis_uop(0).bits.GHR := io.id_uop(0).bits.GHR
              dis_uop(0).bits.branch_pred := io.id_uop(0).bits.branch_pred
              dis_uop(0).bits.btb_hit := io.id_uop(0).bits.btb_hit

              dis_uop(1).valid := true.B
              dis_uop(1).bits.instr := io.id_uop(1).bits.instr
              dis_uop(1).bits.instr_type := io.id_uop(1).bits.instr_type
              dis_uop(1).bits.fu_signals := io.id_uop(1).bits.fu_signals
              dis_uop(1).bits.instr_addr := io.id_uop(1).bits.instr_addr
              dis_uop(1).bits.target_PC := io.id_uop(1).bits.target_PC
              dis_uop(1).bits.GHR := io.id_uop(1).bits.GHR
              dis_uop(1).bits.branch_pred := io.id_uop(1).bits.branch_pred
              dis_uop(1).bits.btb_hit := io.id_uop(1).bits.btb_hit

              //从空闲寄存器列表中读出空闲物理寄存器地址
              dis_uop(0).bits.pdst := freelist(freelist_head)
              dis_uop(1).bits.pdst := freelist(freelist_head + 1.U) //从空闲寄存器列表中读出空闲物理寄存器地址

              head_next := Mux(freelist_head =/= (p.PRF_DEPTH - 32 - 2), freelist_head + 2.U, 0.U)
              /*when(freelist_head + 2.U === freelist_tail){
                freelist_empty := true.B //空闲寄存器列表空
              }*/
              empty_next := head_next === freelist_tail

              when(io.id_uop(0).bits.instr(4,0) === io.id_uop(1).bits.instr(4,0)){
                //两个指令的目的寄存器相同
                rmt(io.id_uop(1).bits.instr(4,0)) := freelist(freelist_head + 1.U) //更新重命名表

                rmt_valid(io.id_uop(1).bits.instr(4,0)) := true.B //更新重命名表有效位
              }.otherwise{
                //两个指令的目的寄存器不同
                rmt(io.id_uop(0).bits.instr(4,0)) := freelist(freelist_head) //更新重命名表
                rmt(io.id_uop(1).bits.instr(4,0)) := freelist(freelist_head + 1.U) //更新重命名表

                rmt_valid(io.id_uop(0).bits.instr(4,0)) := true.B //更新重命名表有效位
                rmt_valid(io.id_uop(1).bits.instr(4,0)) := true.B //更新重命名表有效位
              }

              dis_uop(0).bits.ps1 := Mux(rmt_valid(io.id_uop(0).bits.instr(12,8)), rmt(io.id_uop(0).bits.instr(12,8)), amt(io.id_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
              dis_uop(0).bits.ps2 := Mux(rmt_valid(io.id_uop(0).bits.instr(17,13)), rmt(io.id_uop(0).bits.instr(17,13)), amt(io.id_uop(0).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址

              when(io.id_uop(1).bits.instr(12,8) === io.id_uop(0).bits.instr(4,0)){
                dis_uop(1).bits.ps1 := freelists(freelist_head)
              }.otherwise{
                dis_uop(1).bits.ps1 := Mux(rmt_valid(io.id_uop(1).bits.instr(12,8)), rmt(io.id_uop(1).bits.instr(12,8)), amt(io.id_uop(1).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
              }
              when(io.id_uop(1).bits.instr(17,13) === io.id_uop(0).bits.instr(4,0)){
                dis_uop(1).bits.ps2 := freelists(freelist_head)
              }.otherwise{
                dis_uop(1).bits.ps2 := Mux(rmt_valid(io.id_uop(1).bits.instr(17,13)), rmt(io.id_uop(1).bits.instr(17,13)), amt(io.id_uop(1).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
              }
            }
          }.otherwise{
            rename_ready := io.dis_ready && (freelist_head =/= freelist_tail || !freelist_empty)

            when(rename_ready){
              dis_uop(0).valid := true.B
              dis_uop(0).bits.instr := io.id_uop(0).bits.instr
              dis_uop(0).bits.instr_type := io.id_uop(0).bits.instr_type
              dis_uop(0).bits.fu_signals := io.id_uop(0).bits.fu_signals
              dis_uop(0).bits.instr_addr := io.id_uop(0).bits.instr_addr
              dis_uop(0).bits.target_PC := io.id_uop(0).bits.target_PC
              dis_uop(0).bits.GHR := io.id_uop(0).bits.GHR
              dis_uop(0).bits.branch_pred := io.id_uop(0).bits.branch_pred
              dis_uop(0).bits.btb_hit := io.id_uop(0).bits.btb_hit

              //从空闲寄存器列表中读出空闲物理寄存器地址
              dis_uop(1).valid := true.B
              dis_uop(1).bits.instr := io.id_uop(1).bits.instr
              dis_uop(1).bits.instr_type := io.id_uop(1).bits.instr_type
              dis_uop(1).bits.fu_signals := io.id_uop(1).bits.fu_signals
              dis_uop(1).bits.instr_addr := io.id_uop(1).bits.instr_addr
              dis_uop(1).bits.target_PC := io.id_uop(1).bits.target_PC
              dis_uop(1).bits.GHR := io.id_uop(1).bits.GHR
              dis_uop(1).bits.branch_pred := io.id_uop(1).bits.branch_pred
              dis_uop(1).bits.btb_hit := io.id_uop(1).bits.btb_hit

              dis_uop(0).bits.pdst := freelist(freelist_head) //从空闲寄存器列表中读出空闲物理寄存器地址

              head_next := Mux(freelist_head =/= (p.PRF_DEPTH - 32 - 1).U, freelist_head + 1.U, 0.U)
              /*when(freelist_head + 1.U === freelist_tail){
                freelist_empty := true.B //空闲寄存器列表空
              }*/
              empty_next := head_next === freelist_tail

              rmt(io.id_uop(0).bits.instr(4,0)) := freelist(freelist_head) //更新重命名表
              rmt_valid(io.id_uop(0).bits.instr(4,0)) := true.B //更新重命名表有效位

              dis_uop(0).bits.ps1 := Mux(rmt_valid(io.id_uop(0).bits.instr(12,8)), rmt(io.id_uop(0).bits.instr(12,8)), amt(io.id_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
              dis_uop(0).bits.ps2 := Mux(rmt_valid(io.id_uop(0).bits.instr(17,13)), rmt(io.id_uop(0).bits.instr(17,13)), amt(io.id_uop(0).bits.instr(17,13)))

              when(io.id_uop(1).bits.instr(12,8) === io.id_uop(0).bits.instr(4,0)){
                dis_uop(1).bits.ps1 := freelists(freelist_head)
              }.otherwise{
                dis_uop(1).bits.ps1 := Mux(rmt_valid(io.id_uop(1).bits.instr(12,8)), rmt(io.id_uop(1).bits.instr(12,8)), amt(io.id_uop(1).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
              }
              when(io.id_uop(1).bits.instr(17,13) === io.id_uop(0).bits.instr(4,0)){
                dis_uop(1).bits.ps2 := freelists(freelist_head)
              }.otherwise{
                dis_uop(1).bits.ps2 := Mux(rmt_valid(io.id_uop(1).bits.instr(17,13)), rmt(io.id_uop(1).bits.instr(17,13)), amt(io.id_uop(1).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
              }
            }
          }
        }.otherwise{
          when(io.id_uop(1).instr_type.needPd){
            rename_ready := io.dis_ready && (freelist_head =/= freelist_tail || !freelist_empty)

            when(rename_ready){
              dis_uop(0).valid := true.B
              dis_uop(0).bits.instr := io.id_uop(0).bits.instr
              dis_uop(0).bits.instr_type := io.id_uop(0).bits.instr_type
              dis_uop(0).bits.fu_signals := io.id_uop(0).bits.fu_signals
              dis_uop(0).bits.instr_addr := io.id_uop(0).bits.instr_addr
              dis_uop(0).bits.target_PC := io.id_uop(0).bits.target_PC
              dis_uop(0).bits.GHR := io.id_uop(0).bits.GHR
              dis_uop(0).bits.branch_pred := io.id_uop(0).bits.branch_pred
              dis_uop(0).bits.btb_hit := io.id_uop(0).bits.btb_hit

              //从空闲寄存器列表中读出空闲物理寄存器地址
              dis_uop(1).valid := true.B
              dis_uop(1).bits.instr := io.id_uop(1).bits.instr
              dis_uop(1).bits.instr_type := io.id_uop(1).bits.instr_type
              dis_uop(1).bits.fu_signals := io.id_uop(1).bits.fu_signals
              dis_uop(1).bits.instr_addr := io.id_uop(1).bits.instr_addr
              dis_uop(1).bits.target_PC := io.id_uop(1).bits.target_PC
              dis_uop(1).bits.GHR := io.id_uop(1).bits.GHR
              dis_uop(1).bits.branch_pred := io.id_uop(1).bits.branch_pred
              dis_uop(1).bits.btb_hit := io.id_uop(1).bits.btb_hit

              dis_uop(1).bits.pdst := freelist(freelist_head) //从空闲寄存器列表中读出空闲物理寄存器地址

              head_next := Mux(freelist_head =/= (p.PRF_DEPTH - 32 - 1).U, freelist_head + 1.U, 0.U)
              /*when(freelist_head + 1.U === freelist_tail){
                freelist_empty := true.B //空闲寄存器列表空
              }*/
              empty_next := head_next === freelist_tail

              rmt(io.id_uop(1).bits.instr(4,0)) := freelist(freelist_head) //更新重命名表
              rmt_valid(io.id_uop(1).bits.instr(4,0)) := true.B //更新重命名表有效位

              dis_uop(0).bits.ps1 := Mux(rmt_valid(io.id_uop(0).bits.instr(12,8)), rmt(io.id_uop(0).bits.instr(12,8)), amt(io.id_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
              dis_uop(0).bits.ps2 := Mux(rmt_valid(io.id_uop(0).bits.instr(17,13)), rmt(io.id_uop(0).bits.instr(17,13)), amt(io.id_uop(0).bits.instr(17,13)))

              dis_uop(1).bits.ps1 := Mux(rmt_valid(io.id_uop(1).bits.instr(12,8)), rmt(io.id_uop(1).bits.instr(12,8)), amt(io.id_uop(1).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
              dis_uop(1).bits.ps2 := Mux(rmt_valid(io.id_uop(1).bits.instr(17,13)), rmt(io.id_uop(1).bits.instr(17,13)), amt(io.id_uop(1).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
            }
          }.otherwise{
            rename_ready := io.dis_ready

            dis_uop(0).valid := true.B
            dis_uop(0).bits.instr := io.id_uop(0).bits.instr
            dis_uop(0).bits.instr_type := io.id_uop(0).bits.instr_type
            dis_uop(0).bits.fu_signals := io.id_uop(0).bits.fu_signals
            dis_uop(0).bits.instr_addr := io.id_uop(0).bits.instr_addr
            dis_uop(0).bits.target_PC := io.id_uop(0).bits.target_PC
            dis_uop(0).bits.GHR := io.id_uop(0).bits.GHR
            dis_uop(0).bits.branch_pred := io.id_uop(0).bits.branch_pred
            dis_uop(0).bits.btb_hit := io.id_uop(0).bits.btb_hit

            dis_uop(1).valid := true.B
            dis_uop(1).bits.instr := io.id_uop(1).bits.instr
            dis_uop(1).bits.instr_type := io.id_uop(1).bits.instr_type
            dis_uop(1).bits.fu_signals := io.id_uop(1).bits.fu_signals
            dis_uop(1).bits.instr_addr := io.id_uop(1).bits.instr_addr
            dis_uop(1).bits.target_PC := io.id_uop(1).bits.target_PC
            dis_uop(1).bits.GHR := io.id_uop(1).bits.GHR
            dis_uop(1).bits.branch_pred := io.id_uop(1).bits.branch_pred
            dis_uop(1).bits.btb_hit := io.id_uop(1).bits.btb_hit

            dis_uop(0).bits.ps1 := Mux(rmt_valid(io.id_uop(0).bits.instr(12,8)), rmt(io.id_uop(0).bits.instr(12,8)), amt(io.id_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
            dis_uop(0).bits.ps2 := Mux(rmt_valid(io.id_uop(0).bits.instr(17,13)), rmt(io.id_uop(0).bits.instr(17,13)), amt(io.id_uop(0).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址

            dis_uop(1).bits.ps1 := Mux(rmt_valid(io.id_uop(1).bits.instr(12,8)), rmt(io.id_uop(1).bits.instr(12,8)), amt(io.id_uop(1).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
            dis_uop(1).bits.ps2 := Mux(rmt_valid(io.id_uop(1).bits.instr(17,13)), rmt(io.id_uop(1).bits.instr(17,13)), amt(io.id_uop(1).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
          }
        }
      }
    }
  }

  def hasPd(rob_type : ROBType.Type) : Bool = {
    rob_type === ROBType.Arithmetic || rob_type === ROBType.Jump || rob_type === ROBType.CSR
  }

  when(!flush){
    switch(rob_valid_bits){
      is("b10".U){
        when(io.rob_commitsignal(0).bits.rob_type.hasPd){
          amt(io.rob_commitsignal(0).bits(4,0)) := io.rob_commitsignal(0).bits(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
          tail_next := Mux(freelist_tail =/= (p.PRF_DEPTH - 32 - 1), freelist_tail + 1.U, 0.U)
        }
      }
      is("b11".U){
        when(io.rob_commitsignal(0).bits.rob_type.hasPd){
          when(io.rob_commitsignal(1).bits.rob_type.hasPd){
            tail_next := Mux(freelist_tail =/= (p.PRF_DEPTH - 32 - 2).U, freelist_tail + 2.U, 0.U)
            when(io.rob_commitsignal(0).bits(4,0) === io.rob_commitsignal(1).bits(4,0)){
              amt(io.rob_commitsignal(1).bits(4,0)) := io.rob_commitsignal(1).bits(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
            }.otherwise{
              amt(io.rob_commitsignal(0).bits(4,0)) := io.rob_commitsignal(0).bits(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
              amt(io.rob_commitsignal(1).bits(4,0)) := io.rob_commitsignal(1).bits(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
            }
          }.otherwise{
            tail_next := Mux(freelist_tail =/= (p.PRF_DEPTH - 32 - 1).U, freelist_tail + 1.U, 0.U)
            amt(io.rob_commitsignal(0).bits(4,0)) := io.rob_commitsignal(0).bits(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
          }
        }.otherwise{
          when(io.rob_commitsignal(1).bits.rob_type.hasPd){
            tail_next := Mux(freelist_tail =/= (p.PRF_DEPTH - 32 - 1).U, freelist_tail + 1.U, 0.U)
            amt(io.rob_commitsignal(1).bits(4,0)) := io.rob_commitsignal(1).bits(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
          }
        }
      }
    }
  }

  //freelist_empty的逻辑
  when(!flush){
    freelist_empty := Mux(head_next === tail_next, empty_next, false.B)
  }
}