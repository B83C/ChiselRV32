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

    // To BP
    val taken = Bool()
    // val vGHR = Valid(UInt(p.GHR_WIDTH.W)) // Global history register that when valid means should modify
    // TODO REMOVE IT!

    def isMispredicted: Bool = mispred
}

object ROBType extends ChiselEnum {
    val Arithmetic, Branch, Jump, Store, CSR = Value
}

// 如果实现no-amt方案，并且在branch unit实现一个简单的queue，就不需要这些东西了
class ROBContent(implicit p: Parameters) extends CustomBundle {
    val valid = Bool()
    val instr_addr = UInt(p.XLEN.W) // Instruction address
    val mispred = Bool() // Misprediction flag(1 represents misprediction)
    val completed = Bool() // Completion flag(1 represents completion)

    val pdst = UInt(log2Ceil(p.PRF_DEPTH).W) // Physical destination register
    val rd = UInt(5.W) // Destination register
    val wb = Bool()

    val rob_type = ROBType() // Instruction type

    // Branch info - Can be eliminated, but works for now
    // val btb_hit = BTBHit() // BTB hit flag, 1 represents hit
    val target_PC = UInt(p.XLEN.W) // Target address
    val branch_taken = Bool() // Branch direction
    val GHR = UInt(p.GHR_WIDTH.W) // Global history register

    // Debugging 
    val debug = new InstrDebug
}
// class ROBContent(implicit p: Parameters) extends CustomBundle {
//     val valid = Bool()
//     val instr_addr = UInt(p.XLEN.W) // Instruction address
//     val mispred = Bool() // Misprediction flag(1 represents misprediction)
//     val completed = Bool() // Completion flag(1 represents completion)

//     val last_pdst = UInt(log2Ceil(p.PRF_DEPTH).W) // Physical destination register
//     val rd = UInt(5.W) // Destination register
//     val wb = Bool()

//     val rob_type = ROBType() // Instruction type
//     val payload = UInt(Payload.width.W) // Payload of the ROB entry

//     def as_Arithmetic: ROB_Arithmetic = payload.asTypeOf(new ROB_Arithmetic)
//     def as_Branch: ROB_Branch = payload.asTypeOf(new ROB_Branch)
//     def as_Jump: ROB_Jump = payload.asTypeOf(new ROB_Jump)
//     //def as_Store: ROB_Store = payload.asTypeOf(new ROB_Store)
//     def as_CSR: ROB_CSR = payload.asTypeOf(new ROB_CSR)

//     // Debugging 
//     val debug = new InstrDebug
// }

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
    // 写回信号，仅对特定模块起作用 
    // TO RENAME, LSU
    val rob_commitsignal = ROB.CommitSignal  //广播ROB条目

    // TO BP, and all other modules to signal reset
    val rob_controlsignal = ROB.ControlSignal
}

object ROBState extends ChiselEnum {
    val normal, rollback = Value
}
// ROB采用CORE_WIDTH个BANK的好处如下：
// - 利于硬件实现
// - 容易实现
// 一般不希望fetch packet不填满，但是指令多数情况是填满的，所以一点的开销并不重要
// 另外，无需将指令左对齐，这样对硬件设计很不友好 :/
class ROB(implicit p: Parameters) extends CustomModule {
    val io = IO(new ROBIO())

    val rob = RegInit(VecInit.tabulate(p.ROB_DEPTH)(_ => 0.U.asTypeOf(Vec(p.CORE_WIDTH, new ROBContent())))) //ROB条目

    import ROBState._
    val state = RegInit(normal)

    val depth_bits = log2Ceil(p.ROB_DEPTH)

    val head = RegInit(0.U((depth_bits + 1).W)) //ROB头指针
    val tail = RegInit(0.U((depth_bits + 1).W)) //ROB尾指针
    // Using the MSB to distinguish between full and empty states
    // w- prefix indicates wrapped, which means normal width
    // 有w前缀的whead和wtail才是我们调用rob时用的
    val whead = head(depth_bits - 1, 0)
    val wtail = tail(depth_bits - 1, 0)
    val hwrap = head(depth_bits)
    val twrap = tail(depth_bits)

    val empty = head === tail
    val full = whead === wtail && hwrap =/= twrap

    io.rob_index := tail
    io.rob_empty := empty

    val can_enqueue = !full
    io.rob_uop.ready := can_enqueue

    val commiting_entry = rob(whead)
    val mispred = VecInit(commiting_entry.map(entry => entry.valid && entry.completed && entry.mispred))
    val is_mispredicted = (mispred.asUInt =/= 0.U)

    // 不需要检测mispredict,因为发生mispred之后下一个周期就empty了
    val can_dequeue = !empty && commiting_entry.map(entry => entry.completed || !entry.valid).reduce(_ && _)

    var prev_mispred = false.B
    commiting_entry.zip(mispred).foreach{case (ce, mp) => {
        ce.valid := ce.valid && !prev_mispred 
        prev_mispred = prev_mispred | mp
    }}

    io.rob_commitsignal.valid := (can_dequeue)
    io.rob_commitsignal.bits := (commiting_entry)
    // io.rob_commitsignal.valid := RegNext(can_dequeue)
    // io.rob_commitsignal.bits := RegEnable(commiting_entry, can_dequeue)

    // TODO
    val mispredicted_entry = PriorityMux(mispred.zip(commiting_entry))
    val control_signal = Wire(ROB.ControlSignal)
    control_signal.valid := is_mispredicted
    control_signal.bits.mispred := false.B
    control_signal.bits.taken := false.B
    when(is_mispredicted) {
        tail := head
        (control_signal.bits: Data).waiveAll :<>= (mispredicted_entry: Data).waiveAll

        control_signal.bits.taken := mispredicted_entry.branch_taken
    }

    io.rob_controlsignal.valid := (control_signal.valid)
    io.rob_controlsignal.bits := (control_signal.bits)

    when(can_dequeue) {
        dbg(cf"ROB dequeuing ${io.rob_uop.bits}")
        head := head + 1.U
    }

    when(io.rob_uop.fire) {
        dbg(cf"Enqueuing ${io.rob_uop.bits}")
        rob(wtail) := io.rob_uop.bits.map(uop =>
            convert_to_content(uop)
        )
        tail := tail + 1.U
    }    

    //分配条目的逻辑
    def convert_to_content(dis_uop: Valid[DISPATCH_ROB_uop]): ROBContent = {
        val uop = dis_uop.bits
        val rob_allocate = Wire(new ROBContent)
        //common部分
        (rob_allocate: Data).waiveAll :<>= (uop: Data).waiveAll
        rob_allocate.valid := dis_uop.valid
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
        rob_allocate.wb := false.B

        rob_allocate.target_PC := 0.U
        rob_allocate.branch_taken := false.B
        rob_allocate.GHR := uop.ghr

        // Debugging
        rob_allocate.debug := dis_uop.bits.debug
        // rob_allocate.debug(dis_uop)

        rob_allocate
    }

    val bu_uop = io.bu_uop
    // io.bu_uop.map{ bu_uop => {
    val index = bu_uop.bits.rob_index
    // TODO
    val inner_offset = bu_uop.bits.rob_inner_index

    when(bu_uop.valid){
        // 无需complete，complete信号在wb_uop
        val update_entry = rob(index)(inner_offset)
        (update_entry: Data).waiveAll :<= (bu_uop.bits: Data).waiveAll
        // update_entry.mispred := bu_uop.bits.mispred
        // update_entry.target_PC := bu_uop.bits.target_PC
        // update_entry.branch_taken := bu_uop.bits.branch_taken
    }
    // }}
    io.wb_uop.foreach{ wb_uop =>
        val index = wb_uop.bits.rob_index
        // TODO
        val inner_offset = wb_uop.bits.rob_inner_index
        when(wb_uop.valid) {
            rob(index)(inner_offset).completed := true.B
            rob(index)(inner_offset).wb := wb_uop.bits.pdst_value.valid
        }
    }
}

