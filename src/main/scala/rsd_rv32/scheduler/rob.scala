package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import Utils._

object ROB {
    def CommitSignal(implicit p: Parameters): Valid[Vec[ROBContent]] = {
      Valid(Vec(p.CORE_WIDTH, new ROBContent()))
    }
    def ControlSignal(implicit p: Parameters): Valid[ROBControlSignal] = {
      Valid(new ROBControlSignal)
    }
}
class ROBControlSignal(implicit p: Parameters) extends CustomBundle {
    val mispred = Bool()
    def isMispredicted: Bool = mispred
}

class ROBIO(implicit p: Parameters) extends CustomBundle {
    // 通过Decoupled向dispatch施压，这样实现比较正确
    // 其中外部valid表示dispatch是否正在发送
    // 内部的valid表示指令是否有效
    // 不同层级表示的意义有所不同
    val rob_uop = Flipped(Decoupled(Vec(p.CORE_WIDTH, Valid(new DISPATCH_ROB_uop()))))  //Dispatch Unit的uop,存入条目中

    // 发出下一个载入指令的地址，供Dispatch用
    val rob_index = UInt(log2Ceil(p.ROB_DEPTH).W)
    // 发出empty信号，供Dispatch使用
    val rob_empty = Bool()

    val wb_uop = Vec(p.FU_NUM, Flipped(Valid(new WB_uop()))) // 来自FUs（包括LSU）写回信息
    val bu_uop = Flipped(Valid(new BU_uop())) // 来自bu的uop,更新就绪状态

    // 不让其他从commitsignal判断是否发生mispred，因为这是rob的职责。这里这里不是要论谁对谁错，而是画清责任的界线，这样如果内部实现方式发生了变化，就不影响其他模块，这就是api/abi的作用，目的是为了减轻工作量。
    // 写回信号，仅对rename起作用
    val rob_commitsignal = ROB.CommitSignal  //广播ROB条目
    val rob_controlsignal = ROB.ControlSignal
}

// ROB采用CORE_WIDTH个BANK的好处如下：
// - 利于硬件实现
// - 容易实现
// 一般不希望fetch packet不填满，但是指令多数情况是填满的，所以一点的开销并不重要
// 另外，无需将指令左对齐，这样对硬件设计很不友好 :/
class ROB(implicit p: Parameters) extends CustomModule {
    val io = IO(new ROBIO())
    val rob = RegInit(VecInit.tabulate(p.ROB_DEPTH)(_ => 0.U.asTypeOf(Vec(p.CORE_WIDTH, new ROBContent())))) //ROB条目

    val depth_bits = log2Ceil(p.ROB_DEPTH)
    val head = RegInit(0.U((depth_bits + 1).W)) //ROB头指针
    val tail = RegInit(0.U((depth_bits + 1).W)) //ROB尾指针
    // Using the MSB to distinguish between full and empty states
    // w- prefix indicates wrapped, which means normal width
    val whead = head(depth_bits - 1, 0)
    val wtail = tail(depth_bits - 1, 0)
    val hwrap = head(depth_bits)
    val twrap = tail(depth_bits)

    val empty = head === tail
    val full = whead === wtail && hwrap =/= twrap

    io.rob_index := tail
    io.rob_empty := empty

    val can_dequeue = Wire(Bool())
    io.rob_uop.ready := can_dequeue

    when(can_dequeue) {
        printf(cf"ROB dequeuing ${io.rob_uop.bits}")
        head := head + 1.U
    }

    when(io.rob_uop.fire) {
        printf(cf"Enqueuing ${io.rob_uop.bits}")
        rob(wtail) := io.rob_uop.bits.map(uop =>
            convert_to_content(uop)
        )
        tail := tail + 1.U
    }

    val commiting_entry = rob(whead)
    can_dequeue := commiting_entry.map(entry => entry.completed || !entry.valid).reduce(_ && _)
    io.rob_commitsignal.bits := commiting_entry
    io.rob_commitsignal.valid := can_dequeue

    // TODO
    io.rob_controlsignal.bits.mispred := commiting_entry.exists(entry => entry.valid && entry.mispred)
    io.rob_controlsignal.valid := commiting_entry.exists(entry => entry.valid && entry.mispred)

    //分配条目的逻辑
    def convert_to_content(dis_uop: Valid[DISPATCH_ROB_uop]): ROBContent = {
        val uop = dis_uop.bits
        val rob_allocate = Wire(new ROBContent)
        //common部分
        rob_allocate.valid := dis_uop.valid
        rob_allocate.instr_addr := uop.instr_addr
        rob_allocate.rob_type := MuxLookup(uop.instr_type, ROBType.Arithmetic)(Seq(
            InstrType.ALU -> ROBType.Arithmetic,
            InstrType.MUL -> ROBType.Arithmetic,
            InstrType.DIV_REM -> ROBType.Arithmetic,
            InstrType.LD -> ROBType.Arithmetic,
            InstrType.ST -> ROBType.Store,
            InstrType.Branch -> ROBType.Branch,
            InstrType.Jump -> ROBType.Jump,
            InstrType.CSR -> ROBType.CSR
        ))
        rob_allocate.mispred := false.B
        rob_allocate.completed := false.B
        rob_allocate.payload := 0.U

        //payload部分
        switch(uop.instr_type){
            is(InstrType.ALU, InstrType.MUL, InstrType.DIV_REM, InstrType.LD){
                val arithmetic_payload = Wire(new ROB_Arithmetic())
                arithmetic_payload.pdst := uop.pdst
                arithmetic_payload.rd := uop.rd
                rob_allocate.payload := arithmetic_payload.asUInt
            }
            is(InstrType.Branch){
                val branch_payload = Wire(new ROB_Branch())
                branch_payload.btb_hit := uop.btb_hit
                branch_payload.target_PC := 0.U
                branch_payload.branch_direction := BranchPred.NT
                branch_payload.GHR := uop.GHR
                rob_allocate.payload := branch_payload.asUInt
            }
            is(InstrType.Jump){
                val jump_payload = Wire(new ROB_Jump())
                jump_payload.btb_hit := uop.btb_hit
                jump_payload.target_PC := 0.U
                jump_payload.pdst := uop.pdst
                jump_payload.rd := uop.rd
                jump_payload.GHR := uop.GHR
                rob_allocate.payload := jump_payload.asUInt
            }
            is(InstrType.CSR){
                val csr_payload = Wire(new ROB_CSR)
                csr_payload.pdst := uop.pdst
                csr_payload.rd := uop.rd
                rob_allocate.payload := csr_payload.asUInt
            }
        }
        rob_allocate
    }

    val bu_uop = io.bu_uop
    // io.bu_uop.map{ bu_uop => {
        val index = bu_uop.bits.rob_index
        // TODO
        val inner_offset = bu_uop.bits.rob_inner_index
        when(bu_uop.valid){
            // 无需complete，complete信号在wb_uop
            rob(index)(inner_offset).mispred := bu_uop.bits.mispred
            val branch_payload = WireDefault(rob(index)(inner_offset).as_Branch)
            branch_payload.target_PC := bu_uop.bits.target_PC
            branch_payload.branch_direction := bu_uop.bits.branch_direction
            rob(index)(inner_offset).payload := branch_payload.asUInt
        }.otherwise{
            val jump_payload = WireDefault(rob(index)(inner_offset).as_Jump)
            jump_payload.target_PC := bu_uop.bits.target_PC
            rob(index)(inner_offset).payload := jump_payload.asUInt
        }
    // }}
    io.wb_uop.foreach{ wb_uop =>
        val index = wb_uop.bits.rob_index
        // TODO
        val inner_offset = wb_uop.bits.rob_inner_index
        when(wb_uop.valid) {
            rob(index)(inner_offset).completed := true.B
        }
    }

    /*when(io.rob_uop(0).valid ## io.rob_uop(1).valid === "b10".U){
        switch(io.rob_uop(0).instr_type){
            is(InstrType.ALU, InstrType.MUL, InstrType.DIV, InstrType.LD){
                val temp = WireDefault(0.U(ROBContent.width.W))
                temp := Cat(io.rob_uop(0).instr_addr, ROBType.Arithmetic, false.B, false.B, 0.U((Payload.width - ROB_Arithmetic.width).W), io.rob_uop(0).pdst, io.rob_uop(0).rd)
                rob(rob_tail) := temp
            }
            is(){
                //其他类型指令
            }

            //更新rob_tail and rob_full
        }
        }.elsewhen(io.rob_uop(0).valid ## io.rob_uop(1).valid === "b11".U){
        //处理 “11”
    }*/

    //调试
    // when (true.B) {
    //     printf(p"rob_head=${rob_head} rob_tail=${rob_tail} full=${rob_full}\n")
    //     for (i <- 0 until p.ROB_DEPTH) {
    //         // 通用字段
    //         printf(p"ROB[${"%02d".format(i)}]: addr=0x${Hexadecimal(rob(i).instr_addr)} ")
    //         printf(p"type=${rob(i).rob_type} ") // 打印枚举标签
    //         printf(p"mispred=${rob(i).mispred} done=${rob(i).completed} ")

    //         // 针对不同类型，打印特定字段
    //         switch (rob(i).rob_type) {
    //             is (ROBType.Arithmetic) {
    //                 val a = rob(i).as_Arithmetic
    //                 printf(p"[ARITH] rd=${a.rd} pdst=${a.pdst}\n")
    //             }
    //             //io.rob_uop.ready := !rob_full
    //             is (ROBType.Branch) {
    //                 //io.rob_uop.ready := !rob_full
    //                 val j = rob(i).as_Jump
    //                 printf(p"[JUMP] rd=${j.rd} pdst=${j.pdst} " +
    //                   p"hit=${j.btb_hit} tgt=0x${Hexadecimal(j.target_PC)}\n")
    //             }
    //             is (ROBType.Store) {
    //                 // 如果 Store 有字段，可解包打印；否则只标记
    //                 // //io.rob_uop.ready := !rob_full
    //                 printf(p"[STORE]\n")
    //             }
    //             is (ROBType.CSR) {
    //                 val c = rob(i).as_CSR
    //                 printf(p"[CSR] rd=${c.rd} pdst=${c.pdst}\n")
    //             }
    //         }
    //     }
    //     printf(p"\n\n")
    // }
}

