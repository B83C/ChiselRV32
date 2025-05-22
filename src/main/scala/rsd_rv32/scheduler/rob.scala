package rsd_rv32.scheduler

import chisel3._
import chisel3.util._
import rsd_rv32.common._

/*
class Dispatch_ROB_Interface(implicit p: Parameters) extends CustomBundle {
    val dis_uops = Valid(Vec(p.DISPATCH_WIDTH, new uop()))  //Dispatch Unit的uop

    val rob_empty = Input(Bool())  //ROB空标志(0表示空，1表示非空)
    val rob_head = Input(UInt(log2Ceil(p.ROB_DEPTH))) //ROB头指针
    val rob_tail = Input(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB尾指针
}

class WB_ROB_Interface(implicit p: Parameters) extends CustomBundle {
    val complete_map = Input(Vec(p.FU_NUM, Bool()))  //完成映射表
    val complete_uop = Input(Vec(p.FU_NUM, new uop()))  //来自exu的uop
    val mispred = Input(Bool()) //分支误预测信号
    val if_jump = Input(Bool()) //分支指令跳转信号
}

class ROB_broadcast(implicit p: Parameters) extends CustomBundle {
    val commit_signal = Valid(Vec(p.DISPATCH_WIDTH, UInt((37 + ((34 + p.GHR_WIDTH) max (37 + log2Ceil(p.PRF_DEPTH)))).W))) //ROB条目
}
*/

import Utils._

class ROBIO(implicit p: Parameters) extends CustomBundle {
    val rob_uop = Flipped(Vec(p.CORE_WIDTH, Valid(new DISPATCH_ROB_uop())))  //Dispatch Unit的uop,存入条目中

    val rob_full = Output(Bool())  //ROB满标志(1表示满，无法分配新条目)
    // 没必要
    val rob_head = Output(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB头指针
    val rob_tail = Output(UInt(log2Ceil(p.ROB_DEPTH).W)) //ROB尾指针

    val alu_wb_uop = Vec(p.FU_NUM - p.BU_NUM - p.STU_NUM, Flipped(Valid(new ALU_WB_uop())))  //来自alu0、alu1、mul、div、load pipeline的uop
    val bu_wb_uop = Vec(p.BU_NUM, Flipped(Valid(new BU_WB_uop()))) //来自bu的uop,更新就绪状态
    val stu_wb_uop = Vec(p.STU_NUM, Flipped(Valid(new STPIPE_WB_uop())))  //来自stu的uop,更新就绪状态
    //val LDU_complete_uop = Flipped(Valid(new LDPIPE_WB_uop()))  //来自ldu的uop,更新就绪状态
    // val mispred = Input(Bool()) //分支误预测信号
    // val if_jump = Input(Bool()) //分支指令跳转信号

    val rob_commitsignal = Vec(p.CORE_WIDTH, Valid(new ROBContent()))  //广播ROB条目
}

class ROB(implicit p: Parameters) extends CustomModule {
    val io = IO(new ROBIO())
    val rob = RegInit(VecInit(Seq.fill(p.ROB_DEPTH)(0.U.asTypeOf(new ROBContent())))) //ROB条目
    val rob_head = RegInit(0.U(log2Ceil(p.ROB_DEPTH).W)) //ROB头指针
    val rob_tail = RegInit(0.U(log2Ceil(p.ROB_DEPTH).W)) //ROB尾指针
    val rob_full = RegInit(false.B) //ROB满标志

    val head_next = WireDefault(rob_head)
    val tail_next = WireDefault(rob_tail)
    val full_next = WireDefault(rob_full)

    rob_head := head_next
    rob_tail := tail_next

    io.rob_full := rob_full //ROB满标志输出
    // 没必要
    io.rob_head := rob_head //ROB头指针输出
    io.rob_tail := rob_tail //ROB尾指针输出

    io.rob_commitsignal(0).bits := rob(rob_head)
    val commit0_valid = WireDefault(false.B)
    io.rob_commitsignal(0).valid := commit0_valid

    io.rob_commitsignal(1).bits := rob(Mux(rob_head === (p.ROB_DEPTH - 1).U, 0.U, rob_head + 1.U))
    val commit1_valid = WireDefault(false.B)
    io.rob_commitsignal(1).valid := commit1_valid

    val flush = Wire(Bool()) //Flush信号
    flush := commit0_valid && rob(rob_head).mispred

    val dis_valid_bits = WireDefault(io.rob_uop(0).valid ## io.rob_uop(1).valid)

    //commit的逻辑
    /*val commit0 = WireDefault(false.B)
    val commit0_and_1 = WireDefault(false.B)*/
    commit0_valid := rob(rob_head).completed

    val rob_head_plus1 = rob(Mux(rob_head === (p.PRF_DEPTH - 1).U, 0.U, rob_head + 1.U))
    when(commit0_valid){
        when(!rob(rob_head).mispred && !rob_head_plus1.mispred){
            commit1_valid := rob_head_plus1.completed
        }
    }

    switch(commit0_valid ## commit1_valid){
        is("b11".U){
            head_next := MuxLookup(rob_head, rob_head + 2.U)(Seq(
                (p.PRF_DEPTH - 2).U -> 0.U,
                (p.PRF_DEPTH - 1).U -> 1.U
            ))
        }
        is("b10".U){
            head_next := Mux(rob_head === (p.PRF_DEPTH - 1).U, 0.U, rob_head + 1.U)
        }
    }



    //flush逻辑
    when(flush){
        tail_next := head_next
        rob_full := false.B
        for(i <- 0 until p.ROB_DEPTH){
            rob(i) := 0.U.asTypeOf(new ROBContent())
            //io.rob_uop.ready := !rob_full
        }

    //非flush时为dispatch单元发来的指令分配条目
    val rob_allocate0 = WireDefault(rob(rob_tail))
    val rob_allocate1 = WireDefault(rob(Mux(rob_tail === (p.ROB_DEPTH - 1).U, 0.U, rob_tail + 1.U)))

    rob(rob_tail) := rob_allocate0
    rob(Mux(rob_tail === (p.ROB_DEPTH - 1).U, 0.U, rob_tail + 1.U)) := rob_allocate1

    //分配条目的逻辑

    def allocate_rob_entry(uop: DISPATCH_ROB_uop, rob_allocate: ROBContent): Unit = {
        //common部分
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
    }

    when(!flush){
        switch(dis_valid_bits){
            is("b11".U){
                allocate_rob_entry(io.rob_uop(0).bits, rob_allocate0)
                allocate_rob_entry(io.rob_uop(1).bits, rob_allocate1)

                tail_next := MuxLookup(rob_tail, rob_tail + 2.U)(Seq(
                    (p.ROB_DEPTH - 1).U -> 1.U,
                    (p.ROB_DEPTH - 2).U -> 0.U
                ))

                full_next := tail_next === rob_head
            }
            is("b10".U){
                allocate_rob_entry(io.rob_uop(0).bits, rob_allocate0)

                tail_next := Mux(rob_tail === (p.ROB_DEPTH - 1).U, 0.U, rob_tail + 1.U)

                full_next := tail_next === rob_head
            }
        }
    }

    //非flush时rob_full的逻辑
    when(!flush){
        rob_full := Mux(tail_next === head_next, full_next, false.B)
    }

    //wb逻辑
    for(i <- 0 until p.STU_NUM){
        when(io.stu_wb_uop(i).valid){
            rob(io.stu_wb_uop(i).bits.rob_index).completed := true.B
        }
    }

    for(i <- 0 until p.BU_NUM){
        when(io.bu_wb_uop(i).valid){
            rob(io.bu_wb_uop(i).bits.rob_index).mispred := io.bu_wb_uop(i).bits.mispred
            //io.rob_uop.ready := !rob_full
            rob(io.bu_wb_uop(i).bits.rob_index).completed := true.B
                val branch_payload = WireDefault(rob(io.bu_wb_uop(i).bits.rob_index).as_Branch)
                branch_payload.target_PC := io.bu_wb_uop(i).bits.target_PC
                branch_payload.branch_direction := io.bu_wb_uop(i).bits.branch_direction
                rob(io.bu_wb_uop(i).bits.rob_index).payload := branch_payload.asUInt
            }.otherwise{
                //io.rob_uop.ready := !rob_full
                //rob(io.bu_wb_uop(i).bits.rob_index).completed := true.B
                val jump_payload = WireDefault(rob(io.bu_wb_uop(i).bits.rob_index).as_Jump)
                jump_payload.target_PC := io.bu_wb_uop(i).bits.target_PC
                rob(io.bu_wb_uop(i).bits.rob_index).payload := jump_payload.asUInt
            }
        }
    }

    for(i <- 0 until (p.FU_NUM - p.BU_NUM - p.STU_NUM)){
        when(io.alu_wb_uop(i).valid){
            rob(io.alu_wb_uop(i).bits.rob_index).completed := true.B
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
    when (true.B) {
        printf(p"rob_head=${rob_head} rob_tail=${rob_tail} full=${rob_full}\n")
        for (i <- 0 until p.ROB_DEPTH) {
            // 通用字段
            printf(p"ROB[${"%02d".format(i)}]: addr=0x${Hexadecimal(rob(i).instr_addr)} ")
            printf(p"type=${rob(i).rob_type} ") // 打印枚举标签
            printf(p"mispred=${rob(i).mispred} done=${rob(i).completed} ")

            // 针对不同类型，打印特定字段
            switch (rob(i).rob_type) {
                is (ROBType.Arithmetic) {
                    val a = rob(i).as_Arithmetic
                    printf(p"[ARITH] rd=${a.rd} pdst=${a.pdst}\n")
                }
                //io.rob_uop.ready := !rob_full
                is (ROBType.Branch) {
                    //io.rob_uop.ready := !rob_full
                }
                    val j = rob(i).as_Jump
                    printf(p"[JUMP] rd=${j.rd} pdst=${j.pdst} " +
                      p"hit=${j.btb_hit} tgt=0x${Hexadecimal(j.target_PC)}\n")
                }
                is (ROBType.Store) {
                    // 如果 Store 有字段，可解包打印；否则只标记
                    // //io.rob_uop.ready := !rob_full
                    printf(p"[STORE]\n")
                is (ROBType.CSR) {
                    val c = rob(i).as_CSR
                    printf(p"[CSR] rd=${c.rd} pdst=${c.pdst}\n")
                }
            }
        }
        printf(p"\n\n")
    }
}

