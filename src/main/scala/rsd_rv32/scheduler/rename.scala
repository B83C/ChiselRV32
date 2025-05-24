package rsd_rv32.scheduler

import chisel3._
import chisel3.util._

import rsd_rv32.common._


// 重命名单元将逻辑寄存器地址映射成实际寄存器。逻辑寄存器指的是ISA定义的x0-x31，而实际寄存器数量多于32个，一般可达128个。主要解决WAW，WAR等问题。
class RenameUnit_IO(implicit p: Parameters) extends Bundle {
  // 通过Decoupled向fetch施压，这样实现比较正确
  // 其中外部valid表示fetch是否正在发送
  // 内部的valid表示指令是否有效
  // 不同层级表示的意义有所不同
  // 多余的valid会被优化掉
  val rename_uop = Flipped(Decoupled(Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop()))))  // 来自ID单元的uop

  //with Dispatch
  val dis_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new RENAME_DISPATCH_uop()))) //发往Dispatch单元的uop

  //with ROB
  val rob_commitsignal = Flipped(ROB.CommitSignal)  //ROB提交时的广播信号，rob正常提交指令时更新amt与rmt，发生误预测时对本模块进行恢复
  val rob_controlsignal = Flipped(ROB.ControlSignal) //来自于ROB的控制信号

  //for prf valid bits
  // val amt = (Vec(32,UInt(log2Ceil(p.PRF_DEPTH).W)))
}

/*
  重命名单元采用显示重命名方式消除WAW、WAR，并保留着真实数据依赖RAW。主要维护preg_freelist，RenameMapTable(RMT)和ArchitecturMapTable。其中preg_freelist为FIFO，具有（PRF_DEPTH - 32）个表项。
推测状态重命名表RMT，从上到下依次存储x0～x31所映射到物理寄存器地址。AMT从上到下存储x0～x31所映射的处于架构状态（非推测状态）物理寄存器。
  当ID单元送来指令uop后，若含有源操作数，则从RMT中读出源操作数映射的物理寄存器地址；若含有目的寄存器，则从preg_freelist的head处读出空闲状态的物理寄存器地址，而后更新RMT；
  当指令退休时，根据rob存储的rd条目索引AMT，并把对应物理寄存器地址改为提交的物理寄存器地址，并把旧的物理寄存器地址放入preg_freelist中（freelist的tail + 1 即可）。
  Rename单元通过ROB队首的广播信息来判断是否发生误预测，换言之，误预测指令到ROB队首后才会被处理，因而RMT到恢复只需将AMT复制到RMT，并冲刷掉该模块正在处理的指令。
*/
class RenameUnit(implicit p: Parameters) extends CustomModule {
  val io = IO(new RenameUnit_IO())
  val prf_depth_bits = log2Ceil(p.PRF_DEPTH).W

  val mispred = io.rob_controlsignal.valid && io.rob_controlsignal.bits.isMispredicted
  val rmt = RegInit(VecInit.tabulate(32)(i => (i).U(prf_depth_bits))) // 重命名表，存储逻辑寄存器到物理寄存器的映射关系
  val rmt_valid = RegInit(VecInit.tabulate(32)(_ => false.B)) // 重命名表，存储逻辑寄存器到物理寄存器的映射关系
  val amt = RegInit(VecInit.tabulate(32)(i => (i).U(prf_depth_bits))) // 架构寄存器表，存储逻辑寄存器到物理寄存器的映射关系

  // io.amt := amt

  // 基于CAM的freelist，读写端口宽度均为p.CORE_WIDTH
  // freelist把0地址屏蔽，并初始化rmt的对应关系(相当于预先把0-31的地址分配给rmt)
  // 屏蔽的原因与freelist结构相关  
  val prf_freelist = Module(new FreeListCam(
      p.PRF_DEPTH,
      p.CORE_WIDTH,
      p.CORE_WIDTH,
      maskedRegions = Seq(0 to 0),
      preOccupiedRegion = Seq()
    )) 

  // TODO
  prf_freelist.io.checkpoint := false.B
  prf_freelist.io.restore := false.B
  prf_freelist.io.squeeze := false.B

  val can_rename_all = io.rename_uop.bits.zip(prf_freelist.io.deq_request).map{ case (rename_uop, prf_deq) =>
    !rename_uop.valid || prf_deq.valid
  }.reduce(_ && _)
  val input_ready = io.dis_uop.ready && can_rename_all
  io.rename_uop.ready := input_ready

  val output_valid = io.rename_uop.valid && input_ready
  io.dis_uop.valid := RegNext(output_valid)

  var mapping = VecInit(Seq.fill(p.CORE_WIDTH)(0.U(prf_depth_bits)))
  // var mask = VecInit(Seq.fill(32)(false.B))
  var mask = VecInit(Seq.fill(32)(VecInit(Seq.fill(p.CORE_WIDTH)(false.B))))

  io.rename_uop.bits.zip(io.dis_uop.bits).zip(prf_freelist.io.deq_request).zip(mapping).zipWithIndex.foreach{case ((((rename, dis_out), prf_deq), current_mapping), idx) => {

    val instr_valid = rename.valid
    val dis = Wire(new RENAME_DISPATCH_uop)
    (dis: Data).waiveAll :<>= (rename.bits: Data).waiveAll
    prf_deq.ready := instr_valid

    val dis_instr = Instr.disassemble(rename.bits.instr_ << 7.U) // since renamed uop has truncated instr
    dis.pdst := prf_deq.bits

    when(dis_instr.rd =/= 0.U) {
      rmt(dis_instr.rd) := prf_deq.bits
      rmt_valid(dis_instr.rd) := true.B
    }

    val rmt1 = rmt(dis_instr.rs1)
    val rmt2 = rmt(dis_instr.rs2)
    val rmt_valid1 = rmt_valid(dis_instr.rs1)
    val rmt_valid2 = rmt_valid(dis_instr.rs2)
    val amt1 = amt(dis_instr.rs1)
    val amt2 = amt(dis_instr.rs2)

    val truncated_mapping_1 = (mask(dis_instr.rs1).asUInt & ((1.U << idx) - 1.U))
    val last_mapping_idx1 = OHToUInt(Reverse(PriorityEncoderOH(Reverse(truncated_mapping_1))))
    val truncated_mapping_2 = (mask(dis_instr.rs2).asUInt & ((1.U << idx) - 1.U))
    val last_mapping_idx2 = OHToUInt(Reverse(PriorityEncoderOH(Reverse(truncated_mapping_2))))
    // TODO: There is a better way to do this
    dis.ps1 := Mux(rename.bits.fu_signals.opr1_sel === OprSel.REG, Mux(truncated_mapping_1 =/= 0.U, mapping(last_mapping_idx1), Mux(rmt_valid1, rmt1, amt1)), 0.U)
    dis.ps2 := Mux(rename.bits.fu_signals.opr2_sel === OprSel.REG, Mux(truncated_mapping_2 =/= 0.U, mapping(last_mapping_idx2), Mux(rmt_valid2, rmt2, amt2)), 0.U)
    // dbg(cf"[renaming] renaming rd: ${dis_instr.rd} to ${prf_deq.bits} ${mask}\n${mapping}\n")

    // TODO
    dis_out.valid := RegNext(instr_valid && output_valid)
    dis_out.bits := RegEnable(dis, instr_valid && output_valid)

    // 冲突mask
    mask(dis_instr.rd)(idx) := true.B
    current_mapping := prf_deq.bits

    // Debugging
    dis_out.bits.debug(rename)
  }} 
  
  // 来自ROB的commit
  io.rob_commitsignal.bits.zip(prf_freelist.io.enq_request).foreach{ case (commit, prf_enq) => {
    val wb_valid =  commit.valid && io.rob_commitsignal.valid && commit.wb
    prf_enq.valid := wb_valid
    prf_enq.bits := commit.pdst
    when(wb_valid) {
      amt(commit.rd) := commit.pdst
    }
  }}

  rmt_valid.foreach(v => {
    when(mispred){
      v := false.B
    }
  })

  // val freelist = RegInit(VecInit((32 to p.PRF_DEPTH - 1).map(i => i.U(log2Ceil(p.PRF_DEPTH).W)))) //空闲寄存器列表，存储空闲物理寄存器的地址
  // val freelist_head = RegInit(0.U(log2Ceil(p.PRF_DEPTH - 32).W)) //空闲寄存器列表头指针
  // val freelist_tail = RegInit(0.U(log2Ceil(p.PRF_DEPTH - 32).W)) //空闲寄存器列表尾指针
  // val freelist_empty = RegInit(false.B) //空闲寄存器列表空标志

  // val rename_ready = WireDefault(true.B) //重命名单元准备好接收指令标志
  // val dis_uop = WireDefault(VecInit(Seq.fill(p.CORE_WIDTH)(0.U.asTypeOf(Valid(new RENAME_DISPATCH_uop()))))) //发往Dispatch单元的uop
  // val head_next = WireDefault(freelist_head)
  // val tail_next = WireDefault(freelist_tail)


  // val empty_next = WireDefault(freelist_empty)
  // val reg_dis_uop = RegEnable(dis_uop, VecInit(Seq.fill(p.CORE_WIDTH)(0.U.asTypeOf(Valid(new RENAME_DISPATCH_uop())))),io.dis_ready) //寄存器存储发往Dispatch单元的uop

  // io.rename_ready := rename_ready //反馈给ID单元
  // io.dis_uop := reg_dis_uop //发往Dispatch单元的uop
  // freelist_head := head_next //更新空闲寄存器列表头指针
  // freelist_tail := tail_next //更新空闲寄存器列表尾指针

  // val flush = io.rob_controlsignal.valid && io.rob_controlsignal.bits.isMispredicted

  // val valid_bits = Wire(UInt(2.W))
  // valid_bits := io.rename_uop(0).valid ## io.rename_uop(1).valid //ID单元的指令有效位

  // val rob_valid_bits = Wire(UInt(2.W))
  // rob_valid_bits := io.rob_commitsignal.bits(0).valid ## io.rob_commitsignal.bits(1).valid //ROB单元的指令有效位

  // def needPd(instr_type : InstrType.Type, rd : UInt) : Bool = {
  //   (instr_type === InstrType.ALU || instr_type === InstrType.Jump || instr_type === InstrType.LD || instr_type === InstrType.CSR || instr_type === InstrType.MUL || instr_type === InstrType.DIV_REM) && (rd =/= 0.U)
  // }

  // def hasPd(rob_type : ROBType.Type, rd : UInt) : Bool = {
  //   (rob_type === ROBType.Arithmetic || rob_type === ROBType.Jump || rob_type === ROBType.CSR) && (rd =/= 0.U)
  // }

  // //flush逻辑
  // when(flush){
  //   head_next := tail_next
  //   for(i <- 0 until 32){
  //     rmt_valid(i) := false.B
  //   }
  //   freelist_empty := false.B
    
  //   when(hasPd(io.rob_commitsignal.bits(0).rob_type, io.rob_commitsignal.bits(0).payload(4,0))){
  //     amt(io.rob_commitsignal.bits(0).payload(4,0)) := io.rob_commitsignal.bits(0).payload(5 + log2Ceil(p.PRF_DEPTH) - 1, 5)
  //     freelist(freelist_tail) := amt(io.rob_commitsignal.bits(0).payload(4,0))
  //     tail_next := Mux(freelist_tail === (p.PRF_DEPTH - 32 - 1).U, 0.U, freelist_tail + 1.U)
  //   }
  // }

  // //向下一级递送uop
  // when(!flush){
  //   switch(valid_bits){
  //     is("b10".U){
  //       when(needPd(io.rename_uop(0).bits.instr_type, io.rename_uop(0).bits.instr(4,0))){
  //         rename_ready := io.dis_ready& (freelist_head =/= freelist_tail || !freelist_empty)

  //         when(rename_ready){
  //           dis_uop(0).valid := true.B
  //           dis_uop(0).bits.instr := io.rename_uop(0).bits.instr
  //           dis_uop(0).bits.instr_type := io.rename_uop(0).bits.instr_type
  //           dis_uop(0).bits.fu_signals := io.rename_uop(0).bits.fu_signals
  //           dis_uop(0).bits.instr_addr := io.rename_uop(0).bits.instr_addr
  //           dis_uop(0).bits.target_PC := io.rename_uop(0).bits.target_PC
  //           dis_uop(0).bits.GHR := io.rename_uop(0).bits.GHR
  //           dis_uop(0).bits.branch_pred := io.rename_uop(0).bits.branch_pred
  //           dis_uop(0).bits.btb_hit := io.rename_uop(0).bits.btb_hit

  //           //从空闲寄存器列表中读出空闲物理寄存器地址
  //           dis_uop(0).bits.pdst := freelist(freelist_head)

  //           head_next := Mux(freelist_head === (p.PRF_DEPTH - 32 - 1).U, 0.U, freelist_head + 1.U)
  //           /*when(freelist_head + 1.U === freelist_tail){
  //             freelist_empty := true.B //空闲寄存器列表空
  //           }*/
  //           empty_next := head_next === freelist_tail

  //           rmt(io.rename_uop(0).bits.instr(4,0)) := freelist(freelist_head) //更新重命名表
  //           rmt_valid(io.rename_uop(0).bits.instr(4,0)) := true.B //更新重命名表有效位

  //           dis_uop(0).bits.ps1 := Mux(rmt_valid(io.rename_uop(0).bits.instr(12,8)), rmt(io.rename_uop(0).bits.instr(12,8)), amt(io.rename_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //           dis_uop(0).bits.ps2 := Mux(rmt_valid(io.rename_uop(0).bits.instr(17,13)), rmt(io.rename_uop(0).bits.instr(17,13)), amt(io.rename_uop(0).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
  //         }
  //       }.otherwise{
  //         rename_ready := io.dis_ready
  //         dis_uop(0).valid := true.B
  //         dis_uop(0).bits.instr := io.rename_uop(0).bits.instr
  //         dis_uop(0).bits.instr_type := io.rename_uop(0).bits.instr_type
  //         dis_uop(0).bits.fu_signals := io.rename_uop(0).bits.fu_signals
  //         dis_uop(0).bits.instr_addr := io.rename_uop(0).bits.instr_addr
  //         dis_uop(0).bits.target_PC := io.rename_uop(0).bits.target_PC
  //         dis_uop(0).bits.GHR := io.rename_uop(0).bits.GHR
  //         dis_uop(0).bits.branch_pred := io.rename_uop(0).bits.branch_pred
  //         dis_uop(0).bits.btb_hit := io.rename_uop(0).bits.btb_hit
  //         dis_uop(0).bits.ps1 := Mux(rmt_valid(io.rename_uop(0).bits.instr(12,8)), rmt(io.rename_uop(0).bits.instr(12,8)), amt(io.rename_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //         dis_uop(0).bits.ps2 := Mux(rmt_valid(io.rename_uop(0).bits.instr(17,13)), rmt(io.rename_uop(0).bits.instr(17,13)), amt(io.rename_uop(0).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
  //       }

  //       /*switch(io.rename_uop(0).bits.instr_type){
  //         is(InstrType.Branch, InstrType.ST){
  //           rename_ready := io.dis_ready
  //           dis_uop(0).valid := true.B
  //           dis_uop(0).bits.instr := io.rename_uop(0).bits.instr
  //           dis_uop(0).bits.instr_type := io.rename_uop(0).bits.instr_type
  //           dis_uop(0).bits.fu_signals := io.rename_uop(0).bits.fu_signals
  //           dis_uop(0).bits.instr_addr := io.rename_uop(0).bits.instr_addr
  //           dis_uop(0).bits.target_PC := io.rename_uop(0).bits.target_PC
  //           dis_uop(0).bits.GHR := io.rename_uop(0).bits.GHR
  //           dis_uop(0).bits.branch_pred := io.rename_uop(0).bits.branch_pred
  //           dis_uop(0).bits.btb_hit := io.rename_uop(0).bits.btb_hit
  //         }
  //         is(InstrType.ALU, InstrType.Jump, InstrType.LD, InstrType.CSR, InstrType.MUL, InstrType.DIV_REM){
  //           rename_ready := io.dis_ready& (freelist_head =/= freelist_tail || !freelist_empty)

  //           when(rename_ready){
  //             dis_uop(0).valid := true.B
  //             dis_uop(0).bits.instr := io.rename_uop(0).bits.instr
  //             dis_uop(0).bits.instr_type := io.rename_uop(0).bits.instr_type
  //             dis_uop(0).bits.fu_signals := io.rename_uop(0).bits.fu_signals
  //             dis_uop(0).bits.instr_addr := io.rename_uop(0).bits.instr_addr
  //             dis_uop(0).bits.target_PC := io.rename_uop(0).bits.target_PC
  //             dis_uop(0).bits.GHR := io.rename_uop(0).bits.GHR
  //             dis_uop(0).bits.branch_pred := io.rename_uop(0).bits.branch_pred
  //             dis_uop(0).bits.btb_hit := io.rename_uop(0).bits.btb_hit
          
  //             dis_uop(0).bits.pdst := freelist(freelist_head) //从空闲寄存器列表中读出空闲物理寄存器地址

  //             head_next := Mux(freelist_head =/= (p.PRF_DEPTH - 32 - 1).U, freelist_head + 1.U, 0.U)
  //             /*when(freelist_head + 1.U === freelist_tail){
  //               freelist_empty := true.B //空闲寄存器列表空
  //             }*/
  //             empty_next := head_next === freelist_tail

  //             rmt(io.rename_uop(0).bits.instr(4,0)) := freelist(freelist_head) //更新重命名表
  //             rmt_valid(io.rename_uop(0).bits.instr(4,0)) := true.B //更新重命名表有效位
  //           }
  //         }
  //       }
  //       dis_uop(0).bits.ps1 := Mux(rmt_valid(io.rename_uop(0).bits.instr(12,8)), rmt(io.rename_uop(0).bits.instr(12,8)), amt(io.rename_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //       dis_uop(0).bits.ps2 := Mux(rmt_valid(io.rename_uop(0).bits.instr(17,13)), rmt(io.rename_uop(0).bits.instr(17,13)), amt(io.rename_uop(0).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址 */
  //     }
  //     is("b11".U){
  //       when(needPd(io.rename_uop(0).bits.instr_type, io.rename_uop(0).bits.instr(4,0))){
  //         when(needPd(io.rename_uop(1).bits.instr_type, io.rename_uop(1).bits.instr(4,0))){
  //           rename_ready := io.dis_ready& (freelist_tail - freelist_head >= 2.U || (freelist_head === freelist_tail && !freelist_empty))
  //           when(rename_ready){
  //             dis_uop(0).valid := true.B
  //             dis_uop(0).bits.instr := io.rename_uop(0).bits.instr
  //             dis_uop(0).bits.instr_type := io.rename_uop(0).bits.instr_type
  //             dis_uop(0).bits.fu_signals := io.rename_uop(0).bits.fu_signals
  //             dis_uop(0).bits.instr_addr := io.rename_uop(0).bits.instr_addr
  //             dis_uop(0).bits.target_PC := io.rename_uop(0).bits.target_PC
  //             dis_uop(0).bits.GHR := io.rename_uop(0).bits.GHR
  //             dis_uop(0).bits.branch_pred := io.rename_uop(0).bits.branch_pred
  //             dis_uop(0).bits.btb_hit := io.rename_uop(0).bits.btb_hit

  //             dis_uop(1).valid := true.B
  //             dis_uop(1).bits.instr := io.rename_uop(1).bits.instr
  //             dis_uop(1).bits.instr_type := io.rename_uop(1).bits.instr_type
  //             dis_uop(1).bits.fu_signals := io.rename_uop(1).bits.fu_signals
  //             dis_uop(1).bits.instr_addr := io.rename_uop(1).bits.instr_addr
  //             dis_uop(1).bits.target_PC := io.rename_uop(1).bits.target_PC
  //             dis_uop(1).bits.GHR := io.rename_uop(1).bits.GHR
  //             dis_uop(1).bits.branch_pred := io.rename_uop(1).bits.branch_pred
  //             dis_uop(1).bits.btb_hit := io.rename_uop(1).bits.btb_hit

  //             //从空闲寄存器列表中读出空闲物理寄存器地址
  //             dis_uop(0).bits.pdst := freelist(freelist_head)
  //             dis_uop(1).bits.pdst := freelist(Mux(freelist_head === (p.PRF_DEPTH - 32 - 1).U, 0.U, freelist_head + 1.U)) //从空闲寄存器列表中读出空闲物理寄存器地址

  //             head_next := MuxLookup(freelist_head, freelist_head + 2.U)(Seq(
  //               (p.PRF_DEPTH - 32 - 2).U -> 0.U,
  //               (p.PRF_DEPTH - 32 - 1).U -> 1.U
  //             ))
  //             /*when(freelist_head + 2.U === freelist_tail){
  //               freelist_empty := true.B //空闲寄存器列表空
  //             }*/
  //             empty_next := head_next === freelist_tail

  //             when(io.rename_uop(0).bits.instr(4,0) === io.rename_uop(1).bits.instr(4,0)){
  //               //两个指令的目的寄存器相同
  //               rmt(io.rename_uop(1).bits.instr(4,0)) := freelist(Mux(freelist_head === (p.PRF_DEPTH - 32 - 1).U, 0.U, freelist_head + 1.U)) //更新重命名表

  //               rmt_valid(io.rename_uop(1).bits.instr(4,0)) := true.B //更新重命名表有效位
  //             }.otherwise{
  //               //两个指令的目的寄存器不同
  //               rmt(io.rename_uop(0).bits.instr(4,0)) := freelist(freelist_head) //更新重命名表
  //               rmt(io.rename_uop(1).bits.instr(4,0)) := freelist(Mux(freelist_head === (p.PRF_DEPTH - 32 - 1).U, 0.U, freelist_head + 1.U)) //更新重命名表

  //               rmt_valid(io.rename_uop(0).bits.instr(4,0)) := true.B //更新重命名表有效位
  //               rmt_valid(io.rename_uop(1).bits.instr(4,0)) := true.B //更新重命名表有效位
  //             }

  //             dis_uop(0).bits.ps1 := Mux(rmt_valid(io.rename_uop(0).bits.instr(12,8)), rmt(io.rename_uop(0).bits.instr(12,8)), amt(io.rename_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //             dis_uop(0).bits.ps2 := Mux(rmt_valid(io.rename_uop(0).bits.instr(17,13)), rmt(io.rename_uop(0).bits.instr(17,13)), amt(io.rename_uop(0).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址

  //             when(io.rename_uop(1).bits.instr(12,8) === io.rename_uop(0).bits.instr(4,0)){
  //               dis_uop(1).bits.ps1 := freelist(freelist_head)
  //             }.otherwise{
  //               dis_uop(1).bits.ps1 := Mux(rmt_valid(io.rename_uop(1).bits.instr(12,8)), rmt(io.rename_uop(1).bits.instr(12,8)), amt(io.rename_uop(1).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //             }
  //             when(io.rename_uop(1).bits.instr(17,13) === io.rename_uop(0).bits.instr(4,0)){
  //               dis_uop(1).bits.ps2 := freelist(freelist_head)
  //             }.otherwise{
  //               dis_uop(1).bits.ps2 := Mux(rmt_valid(io.rename_uop(1).bits.instr(17,13)), rmt(io.rename_uop(1).bits.instr(17,13)), amt(io.rename_uop(1).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
  //             }
  //           }
  //         }.otherwise{
  //           rename_ready := io.dis_ready& (freelist_head =/= freelist_tail || !freelist_empty)

  //           when(rename_ready){
  //             dis_uop(0).valid := true.B
  //             dis_uop(0).bits.instr := io.rename_uop(0).bits.instr
  //             dis_uop(0).bits.instr_type := io.rename_uop(0).bits.instr_type
  //             dis_uop(0).bits.fu_signals := io.rename_uop(0).bits.fu_signals
  //             dis_uop(0).bits.instr_addr := io.rename_uop(0).bits.instr_addr
  //             dis_uop(0).bits.target_PC := io.rename_uop(0).bits.target_PC
  //             dis_uop(0).bits.GHR := io.rename_uop(0).bits.GHR
  //             dis_uop(0).bits.branch_pred := io.rename_uop(0).bits.branch_pred
  //             dis_uop(0).bits.btb_hit := io.rename_uop(0).bits.btb_hit

  //             //从空闲寄存器列表中读出空闲物理寄存器地址
  //             dis_uop(1).valid := true.B
  //             dis_uop(1).bits.instr := io.rename_uop(1).bits.instr
  //             dis_uop(1).bits.instr_type := io.rename_uop(1).bits.instr_type
  //             dis_uop(1).bits.fu_signals := io.rename_uop(1).bits.fu_signals
  //             dis_uop(1).bits.instr_addr := io.rename_uop(1).bits.instr_addr
  //             dis_uop(1).bits.target_PC := io.rename_uop(1).bits.target_PC
  //             dis_uop(1).bits.GHR := io.rename_uop(1).bits.GHR
  //             dis_uop(1).bits.branch_pred := io.rename_uop(1).bits.branch_pred
  //             dis_uop(1).bits.btb_hit := io.rename_uop(1).bits.btb_hit

  //             dis_uop(0).bits.pdst := freelist(freelist_head) //从空闲寄存器列表中读出空闲物理寄存器地址

  //             head_next := Mux(freelist_head =/= (p.PRF_DEPTH - 32 - 1).U, freelist_head + 1.U, 0.U)
  //             /*when(freelist_head + 1.U === freelist_tail){
  //               freelist_empty := true.B //空闲寄存器列表空
  //             }*/
  //             empty_next := head_next === freelist_tail

  //             rmt(io.rename_uop(0).bits.instr(4,0)) := freelist(freelist_head) //更新重命名表
  //             rmt_valid(io.rename_uop(0).bits.instr(4,0)) := true.B //更新重命名表有效位

  //             dis_uop(0).bits.ps1 := Mux(rmt_valid(io.rename_uop(0).bits.instr(12,8)), rmt(io.rename_uop(0).bits.instr(12,8)), amt(io.rename_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //             dis_uop(0).bits.ps2 := Mux(rmt_valid(io.rename_uop(0).bits.instr(17,13)), rmt(io.rename_uop(0).bits.instr(17,13)), amt(io.rename_uop(0).bits.instr(17,13)))

  //             when(io.rename_uop(1).bits.instr(12,8) === io.rename_uop(0).bits.instr(4,0)){
  //               dis_uop(1).bits.ps1 := freelist(freelist_head)
  //             }.otherwise{
  //               dis_uop(1).bits.ps1 := Mux(rmt_valid(io.rename_uop(1).bits.instr(12,8)), rmt(io.rename_uop(1).bits.instr(12,8)), amt(io.rename_uop(1).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //             }
  //             when(io.rename_uop(1).bits.instr(17,13) === io.rename_uop(0).bits.instr(4,0)){
  //               dis_uop(1).bits.ps2 := freelist(freelist_head)
  //             }.otherwise{
  //               dis_uop(1).bits.ps2 := Mux(rmt_valid(io.rename_uop(1).bits.instr(17,13)), rmt(io.rename_uop(1).bits.instr(17,13)), amt(io.rename_uop(1).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
  //             }
  //           }
  //         }
  //       }.otherwise{
  //         when(needPd(io.rename_uop(1).bits.instr_type, io.rename_uop(1).bits.instr(4,0))){
  //           rename_ready := io.dis_ready& (freelist_head =/= freelist_tail || !freelist_empty)

  //           when(rename_ready){
  //             dis_uop(0).valid := true.B
  //             dis_uop(0).bits.instr := io.rename_uop(0).bits.instr
  //             dis_uop(0).bits.instr_type := io.rename_uop(0).bits.instr_type
  //             dis_uop(0).bits.fu_signals := io.rename_uop(0).bits.fu_signals
  //             dis_uop(0).bits.instr_addr := io.rename_uop(0).bits.instr_addr
  //             dis_uop(0).bits.target_PC := io.rename_uop(0).bits.target_PC
  //             dis_uop(0).bits.GHR := io.rename_uop(0).bits.GHR
  //             dis_uop(0).bits.branch_pred := io.rename_uop(0).bits.branch_pred
  //             dis_uop(0).bits.btb_hit := io.rename_uop(0).bits.btb_hit

  //             //从空闲寄存器列表中读出空闲物理寄存器地址
  //             dis_uop(1).valid := true.B
  //             dis_uop(1).bits.instr := io.rename_uop(1).bits.instr
  //             dis_uop(1).bits.instr_type := io.rename_uop(1).bits.instr_type
  //             dis_uop(1).bits.fu_signals := io.rename_uop(1).bits.fu_signals
  //             dis_uop(1).bits.instr_addr := io.rename_uop(1).bits.instr_addr
  //             dis_uop(1).bits.target_PC := io.rename_uop(1).bits.target_PC
  //             dis_uop(1).bits.GHR := io.rename_uop(1).bits.GHR
  //             dis_uop(1).bits.branch_pred := io.rename_uop(1).bits.branch_pred
  //             dis_uop(1).bits.btb_hit := io.rename_uop(1).bits.btb_hit

  //             dis_uop(1).bits.pdst := freelist(freelist_head) //从空闲寄存器列表中读出空闲物理寄存器地址

  //             head_next := Mux(freelist_head =/= (p.PRF_DEPTH - 32 - 1).U, freelist_head + 1.U, 0.U)
  //             /*when(freelist_head + 1.U === freelist_tail){
  //               freelist_empty := true.B //空闲寄存器列表空
  //             }*/
  //             empty_next := head_next === freelist_tail

  //             rmt(io.rename_uop(1).bits.instr(4,0)) := freelist(freelist_head) //更新重命名表
  //             rmt_valid(io.rename_uop(1).bits.instr(4,0)) := true.B //更新重命名表有效位

  //             dis_uop(0).bits.ps1 := Mux(rmt_valid(io.rename_uop(0).bits.instr(12,8)), rmt(io.rename_uop(0).bits.instr(12,8)), amt(io.rename_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //             dis_uop(0).bits.ps2 := Mux(rmt_valid(io.rename_uop(0).bits.instr(17,13)), rmt(io.rename_uop(0).bits.instr(17,13)), amt(io.rename_uop(0).bits.instr(17,13)))

  //             dis_uop(1).bits.ps1 := Mux(rmt_valid(io.rename_uop(1).bits.instr(12,8)), rmt(io.rename_uop(1).bits.instr(12,8)), amt(io.rename_uop(1).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //             dis_uop(1).bits.ps2 := Mux(rmt_valid(io.rename_uop(1).bits.instr(17,13)), rmt(io.rename_uop(1).bits.instr(17,13)), amt(io.rename_uop(1).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
  //           }
  //         }.otherwise{
  //           rename_ready := io.dis_ready
  //           dis_uop(0).valid := true.B
  //           dis_uop(0).bits.instr := io.rename_uop(0).bits.instr
  //           dis_uop(0).bits.instr_type := io.rename_uop(0).bits.instr_type
  //           dis_uop(0).bits.fu_signals := io.rename_uop(0).bits.fu_signals
  //           dis_uop(0).bits.instr_addr := io.rename_uop(0).bits.instr_addr
  //           dis_uop(0).bits.target_PC := io.rename_uop(0).bits.target_PC
  //           dis_uop(0).bits.GHR := io.rename_uop(0).bits.GHR
  //           dis_uop(0).bits.branch_pred := io.rename_uop(0).bits.branch_pred
  //           dis_uop(0).bits.btb_hit := io.rename_uop(0).bits.btb_hit

  //           dis_uop(1).valid := true.B
  //           dis_uop(1).bits.instr := io.rename_uop(1).bits.instr
  //           dis_uop(1).bits.instr_type := io.rename_uop(1).bits.instr_type
  //           dis_uop(1).bits.fu_signals := io.rename_uop(1).bits.fu_signals
  //           dis_uop(1).bits.instr_addr := io.rename_uop(1).bits.instr_addr
  //           dis_uop(1).bits.target_PC := io.rename_uop(1).bits.target_PC
  //           dis_uop(1).bits.GHR := io.rename_uop(1).bits.GHR
  //           dis_uop(1).bits.branch_pred := io.rename_uop(1).bits.branch_pred
  //           dis_uop(1).bits.btb_hit := io.rename_uop(1).bits.btb_hit

  //           dis_uop(0).bits.ps1 := Mux(rmt_valid(io.rename_uop(0).bits.instr(12,8)), rmt(io.rename_uop(0).bits.instr(12,8)), amt(io.rename_uop(0).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //           dis_uop(0).bits.ps2 := Mux(rmt_valid(io.rename_uop(0).bits.instr(17,13)), rmt(io.rename_uop(0).bits.instr(17,13)), amt(io.rename_uop(0).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址

  //           dis_uop(1).bits.ps1 := Mux(rmt_valid(io.rename_uop(1).bits.instr(12,8)), rmt(io.rename_uop(1).bits.instr(12,8)), amt(io.rename_uop(1).bits.instr(12,8))) //读出源操作数映射的物理寄存器地址
  //           dis_uop(1).bits.ps2 := Mux(rmt_valid(io.rename_uop(1).bits.instr(17,13)), rmt(io.rename_uop(1).bits.instr(17,13)), amt(io.rename_uop(1).bits.instr(17,13))) //读出源操作数映射的物理寄存器地址
  //         }
  //       }
  //     }
  //   }
  // }

  
  // //非flush时，更新amt、freelist
  // when(!flush){
  //   switch(rob_valid_bits){
  //     is("b10".U){
  //       when(hasPd(io.rob_commitsignal.bits(0).rob_type, io.rob_commitsignal.bits(0).payload(4,0))){
  //         amt(io.rob_commitsignal.bits(0).payload(4,0)) := io.rob_commitsignal.bits(0).payload(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
  //         freelist(freelist_tail) := amt(io.rob_commitsignal.bits(0).payload(4,0))
  //         tail_next := Mux(freelist_tail =/= (p.PRF_DEPTH - 32 - 1).U, freelist_tail + 1.U, 0.U)
  //       }
  //     }
  //     is("b11".U){
  //       when(hasPd(io.rob_commitsignal.bits(0).rob_type, io.rob_commitsignal.bits(0).payload(4,0))){
  //         when(hasPd(io.rob_commitsignal.bits(1).rob_type, io.rob_commitsignal.bits(1).payload(4,0))){
  //           tail_next := MuxLookup(freelist_tail, freelist_tail + 2.U)(Seq(
  //             (p.PRF_DEPTH - 32 - 2).U -> 0.U,
  //             (p.PRF_DEPTH - 32 - 1).U -> 1.U
  //           ))
  //           when(io.rob_commitsignal.bits(0).payload(4,0) === io.rob_commitsignal.bits(1).payload(4,0)){
  //             amt(io.rob_commitsignal.bits(1).payload(4,0)) := io.rob_commitsignal.bits(1).payload(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
  //             freelist(freelist_tail) := amt(io.rob_commitsignal.bits(0).payload(4,0))
  //             freelist(Mux(freelist_tail === (p.PRF_DEPTH - 32 - 1).U, 0.U, freelist_tail + 1.U)) := io.rob_commitsignal.bits(0).payload(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
  //           }.otherwise{
  //             amt(io.rob_commitsignal.bits(0).payload(4,0)) := io.rob_commitsignal.bits(0).payload(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
  //             amt(io.rob_commitsignal.bits(1).payload(4,0)) := io.rob_commitsignal.bits(1).payload(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
  //             freelist(freelist_tail) := amt(io.rob_commitsignal.bits(0).payload(4,0))
  //             freelist(Mux(freelist_tail === (p.PRF_DEPTH - 32 - 1).U, 0.U, freelist_tail + 1.U)) := amt(io.rob_commitsignal.bits(0).payload(4,0))
  //           }
  //         }.otherwise{
  //           tail_next := Mux(freelist_tail =/= (p.PRF_DEPTH - 32 - 1).U, freelist_tail + 1.U, 0.U)
  //           amt(io.rob_commitsignal.bits(0).payload(4,0)) := io.rob_commitsignal.bits(0).payload(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
  //           freelist(freelist_tail) := amt(io.rob_commitsignal.bits(0).payload(4,0))
  //         }
  //       }.otherwise{
  //         when(hasPd(io.rob_commitsignal.bits(1).rob_type, io.rob_commitsignal.bits(1).payload(4,0))){
  //           tail_next := Mux(freelist_tail =/= (p.PRF_DEPTH - 32 - 1).U, freelist_tail + 1.U, 0.U)
  //           amt(io.rob_commitsignal.bits(1).payload(4,0)) := io.rob_commitsignal.bits(1).payload(5 + log2Ceil(p.PRF_DEPTH) - 1,5)
  //           freelist(freelist_tail) := amt(io.rob_commitsignal.bits(1).payload(4,0))
  //         }
  //       }
  //     }
  //   }
  // }

  // //非flush时，freelist_empty的逻辑
  // when(!flush){
  //   freelist_empty := Mux(head_next === tail_next, empty_next, false.B)
  // }

  // // printf("\nstart printing-------------------------------------------\n")

  // // Debugging
  // when(io.rename_ready) {
  //   printf(cf"[Rename -> Dispatch] PC: ${io.dis_uop(0).bits.instr_addr} ")
  //   for(i <- 0 until p.CORE_WIDTH) {
  //     when(io.dis_uop(i).valid) {
  //       val pd = io.dis_uop(i).bits.pdst 
  //       val rd = io.dis_uop(i).bits.instr(4, 0) //Truncated instr
  //       val valid = io.dis_uop(i).valid
  //       printf(cf" rd(${i})=${rd} to pdst${pd} vaild : ${valid}| ")
  //     }
  //   }
  //   printf("\n")
  // }

  // io.dis_uop.zip(io.rename_uop).foreach{case (x, y) => {
  //     x.bits.debug(y.bits, y.valid)
  // }}

  //output
  // printf("\nrename_ready: %d\n", io.rename_ready)

  // printf("\n--dis_uop(0)\n")
  // printf("valid: %d\n", io.dis_uop(0).valid)
  // printf("pdst: %d\n", io.dis_uop(0).bits.pdst)
  // printf("ps1: %d\n", io.dis_uop(0).bits.ps1)
  // printf("ps2: %d\n", io.dis_uop(0).bits.ps2)
  // printf("\n--dis_uop(1)\n")
  // printf("valid: %d\n", io.dis_uop(1).valid)
  // printf("pdst: %d\n", io.dis_uop(1).bits.pdst)
  // printf("ps1: %d\n", io.dis_uop(1).bits.ps1)
  // printf("ps2: %d\n", io.dis_uop(1).bits.ps2)

  // //signals inside
  // printf("\n--rmt\n")
  // for(i <- 0 until 32){
  //   printf("rmt_%d: valid = %d, value = %d\n", i.U, rmt_valid(i), rmt(i))
  // }

  // printf("\n--amt\n")
  // for(i <- 0 until 32){
  //   printf("amt_%d: %d\n", i.U, amt(i))
  // }

  // printf("\n--freelist\n")
  // printf("freelist_head: %d, freelist_tail: %d, freelist_empty: %d\n", freelist_head, freelist_tail, freelist_empty)
  // for(i <- 0 until p.PRF_DEPTH - 32){
  //   printf("freelist_%d: %d\n", i.U, freelist(i))
  // }
}
