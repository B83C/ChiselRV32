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
  val rob_controlsignal = Flipped(new ROBControlSignal) //来自于ROB的控制信号

  val prf_busys_write = Vec(p.CORE_WIDTH, Valid(UInt(log2Ceil(p.PRF_DEPTH).W)))

  //for prf valid bits
  // val amt = (Vec(32,UInt(log2Ceil(p.PRF_DEPTH).W)))
}

//   重命名单元采用显示重命名方式消除WAW、WAR，并保留着真实数据依赖RAW。主要维护preg_freelist，RenameMapTable(RMT)和ArchitecturMapTable。其中preg_freelist为FIFO，具有（PRF_DEPTH - 32）个表项。
// 推测状态重命名表RMT，从上到下依次存储x0～x31所映射到物理寄存器地址。AMT从上到下存储x0～x31所映射的处于架构状态（非推测状态）物理寄存器。
//   当ID单元送来指令uop后，若含有源操作数，则从RMT中读出源操作数映射的物理寄存器地址；若含有目的寄存器，则从preg_freelist的head处读出空闲状态的物理寄存器地址，而后更新RMT；
//   当指令退休时，根据rob存储的rd条目索引AMT，并把对应物理寄存器地址改为提交的物理寄存器地址，并把旧的物理寄存器地址放入preg_freelist中（freelist的tail + 1 即可）。
//   Rename单元通过ROB队首的广播信息来判断是否发生误预测，换言之，误预测指令到ROB队首后才会被处理，因而RMT到恢复只需将AMT复制到RMT，并冲刷掉该模块正在处理的指令。
class RenameUnit(implicit p: Parameters) extends CustomModule {
  val io = IO(new RenameUnit_IO())
  val prf_depth_bits = log2Ceil(p.PRF_DEPTH).W

  // val mispred = io.rob_controlsignal.valid && io.rob_controlsignal.bits.isMispredicted
  val should_flush = io.rob_controlsignal.shouldFlush

  // val rmt = RegInit(VecInit.tabulate(32)(i => (i).U(prf_depth_bits))) // 重命名表，存储逻辑寄存器到物理寄存器的映射关系
  // val amt = RegInit(VecInit.tabulate(32)(i => (i).U(prf_depth_bits))) // 架构寄存器表，存储逻辑寄存器到物理寄存器的映射关系
  val rmt = RegInit(VecInit(Seq.fill(32)(0.U(prf_depth_bits)))) // 重命名表，存储逻辑寄存器到物理寄存器的映射关系
  val amt = RegInit(VecInit(Seq.fill(32)(0.U(prf_depth_bits)))) // 架构寄存器表，存储逻辑寄存器到物理寄存器的映射关系

  // io.amt := amt

  // 基于CAM的freelist，读写端口宽度均为p.CORE_WIDTH
  // freelist把0地址屏蔽，并初始化rmt的对应关系(相当于预先把0-31的地址分配给rmt)
  // 屏蔽的原因与freelist结构相关  
  val prf_freelist = Module(new FreeListCam(
      p.PRF_DEPTH,
      p.CORE_WIDTH,
      p.CORE_WIDTH,
      maskedRegions = Seq(0 to 0),
      preOccupiedRegion = Seq(),
      needCheckpoint = true,

      sharedState = true, // DELETE IT
    )) 


  val amt_freelist_mapping = RegInit(VecInit(0.U(p.PRF_DEPTH.W).asBools))
  // TODO
  prf_freelist.io.squeeze := false.B
  // TODO: since the commitsignal has output reg, it should be delayed 
  prf_freelist.io.restore.get := RegNext(io.rob_controlsignal.restore_amt)
  prf_freelist.io.restore_mapping.get := amt_freelist_mapping

  val mapping_1 = prf_freelist.io.state.get.asUInt
  val mapping_2 = amt_freelist_mapping.asUInt

  dontTouch(mapping_1)
  dontTouch(mapping_2)

  // when(mispred) {
  //   printf(cf"AMT RESTORE\n")
  //   amt_freelist_mapping.zipWithIndex.foreach{case (m, i) => {
  //     when(m === true.B){
  //       printf(cf"${i} occupied\n")
  //     }
  //   }}
  // }

  val can_rename_all = io.rename_uop.bits.zip(prf_freelist.io.deq_request).map{ case (rename_uop, prf_deq) =>
    // 包含三种情况：
    // 指令无效、无写入地址、以及有写入地址并且可获得新的地址
    !rename_uop.valid || !prf_deq.ready || prf_deq.valid
  }.reduce(_ && _)

  val operation_ready = can_rename_all
  val downstream_ready = io.dis_uop.ready
  val ack = operation_ready && downstream_ready

  io.rename_uop.ready := ack

  withReset(reset.asBool || should_flush)  {
    val rmt_valid = RegInit(VecInit.tabulate(32)(_ => false.B)) // 重命名表，存储逻辑寄存器到物理寄存器的映射关系

    var mapping = VecInit(Seq.fill(p.CORE_WIDTH)(0.U(prf_depth_bits)))
    // var mask = VecInit(Seq.fill(32)(false.B))
    var mask = VecInit(Seq.fill(32)(VecInit(Seq.fill(p.CORE_WIDTH)(false.B))))

    io.dis_uop.bits := RegEnable(VecInit(io.rename_uop.bits.zip(io.dis_uop.bits).zip(prf_freelist.io.deq_request).zip(prf_freelist.io.enq_request).zip(mapping).zip(io.prf_busys_write).zipWithIndex.map{case ((((((in_uop, out_uop), prf_deq), prf_enq), current_mapping), prf_busy), idx) => {

      val instr_valid = in_uop.valid && io.rename_uop.valid
      val input = in_uop.bits 

      val out_uop_w = Wire(Valid(new RENAME_DISPATCH_uop))
      (out_uop_w: Data).waiveAll :<>= (in_uop: Data).waiveAll

      // Valid bit check is a bit redundant
      val writes_to_reg = in_uop.bits.rd.valid && instr_valid && downstream_ready
      // TODO
      prf_deq.ready := writes_to_reg

      prf_busy.valid := writes_to_reg
      prf_busy.bits := prf_deq.bits
      //TODO 
      // prf_enq.valid := writes_to_reg
      // prf_enq.bits := DontCare

      out_uop_w.bits.pdst.valid := writes_to_reg
      out_uop_w.bits.pdst.bits := prf_deq.bits

      when(writes_to_reg) {
        rmt(input.rd.bits) := prf_deq.bits
        rmt_valid(input.rd.bits) := true.B
      }

      val rm1 = rmt(input.rs1)
      val rm2 = rmt(input.rs2)
      val rm_valid1 = rmt_valid(input.rs1)
      val rm_valid2 = rmt_valid(input.rs2)
      val am1 = amt(input.rs1)
      val am2 = amt(input.rs2)

      val truncated_mapping_1 = (mask(input.rs1).asUInt & ((1.U << idx) - 1.U))
      val last_mapping_idx1 = OHToUInt(Reverse(PriorityEncoderOH(Reverse(truncated_mapping_1))))
      val truncated_mapping_2 = (mask(input.rs2).asUInt & ((1.U << idx) - 1.U))
      val last_mapping_idx2 = OHToUInt(Reverse(PriorityEncoderOH(Reverse(truncated_mapping_2))))
      // TODO: There is a better way to do this
      out_uop_w.bits.ps1 := Mux(input.opr1_sel === OprSel.REG, Mux(truncated_mapping_1 =/= 0.U, mapping(last_mapping_idx1), Mux(rm_valid1, rm1, am1)), 127.U)
      out_uop_w.bits.ps2 := Mux(input.opr2_sel === OprSel.REG, Mux(truncated_mapping_2 =/= 0.U, mapping(last_mapping_idx2), Mux(rm_valid2, rm2, am2)), 127.U)


      // 冲突mask
      current_mapping := 0.U
      when(writes_to_reg) {
        mask(input.rd.bits)(idx) := true.B
        current_mapping := prf_deq.bits
      }
      // Debugging
      out_uop_w.bits.debug := input.debug
      out_uop_w
    }}), ack) 

    // io.dis_uop.valid := RegNext(io.rename_uop.valid && ack)
    io.dis_uop.valid := RegEnable(io.rename_uop.valid, false.B, ack)
  
    // 来自ROB的commit
    var commit_rd_mask = 0.U(32.W)
    // var commit_rd_mask = VecInit(Seq.fill(32)(VecInit(Seq.fill(p.CORE_WIDTH)(false.B))))
    io.rob_commitsignal.bits.zip(prf_freelist.io.enq_request).zipWithIndex.reverse.foreach{ case ((commit, prf_enq), idx) => {
      val rd = commit.rd.bits
      val pdst = commit.pdst.bits
      val wb_valid =  commit.valid && io.rob_commitsignal.valid && commit.pdst.valid
      prf_enq.bits := pdst
      prf_enq.valid := wb_valid && rmt(rd) =/= pdst
      when(wb_valid) {
        // RMT bug

        // AMT - Replaces old mappings, in amt, there could only be 32 pdst valid at a time
        val commit_rd_mask_vec = VecInit(commit_rd_mask.asBools)
        when(commit_rd_mask_vec(rd) === false.B) {
          val amt_mapping = amt(commit.rd.bits)
          when(amt_mapping =/= 0.U) {
            amt_freelist_mapping(amt_mapping) := false.B
          }
          amt_freelist_mapping(pdst) := true.B
          amt_mapping := pdst
        }
      }
      commit_rd_mask = commit_rd_mask | (wb_valid.asUInt << rd)
    }}

    //for debugging
    // when (true.B) {
      // val buf: UInt = prf_freelist.io.state.get.asUInt
      // printf(cf"${io.dis_uop.bits(0).bits.instr_addr}%x \n")
      // printf(cf"b${buf}%b \n")
      // printf(cf"a${amt_freelist_mapping.asUInt}%b \n")
    // }
  }
}
