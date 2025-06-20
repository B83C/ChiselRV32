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

// Only the the oldest valid bu_signal from bfu is broadcasted.
// Any younger bu_signal will only go through branch_kill_mask but not bu_signals
// the bu_signals here is valid only when the branch_mask of the bu_signal matches the
// instruction at the head.
// The bu_signal is fired when the oldest bu_signal is fired from bfu, so it can happen before being commited.
// Therefore, another restore_amt signal is required at the moment so as to restore the amt when
// the offending instruction reaches the head of rob.
// The branch_kill_mask is used to inform every modules that there is a mispredition going on and the
// instructions matching the branch should be killed. This should be broadcasted asap since any misprediction
// will still halt the younger instructions.
class ROBControlSignal(implicit p: Parameters) extends CustomBundle {
    // To all modules
    // Can be compressed
    // val branch_kill_mask = Valid(UInt(p.BRANCH_MASK_WIDTH.W))

    // Can be 
    val restore_amt = Bool()

    val should_flush = Bool()

    // Used to inform other modules to stop receiving new instructions and clear instructions if needed
    def shouldFlush: Bool = should_flush
    def shouldBeKilled() : Bool = shouldFlush
    def shouldBeKilled(branch_mask: Vec[Bool]) : Bool = shouldFlush
    // def shouldBeKilled(branch_mask: Vec[Bool]) : Bool = shouldFlush && (branch_kill_mask.bits & branch_mask.asUInt) =/= 0.U 
    def shouldBeKilled(branch_mask: UInt) : Bool = shouldBeKilled(VecInit(branch_mask.asBools))
    def shouldBeKilled[T <: uop](uop: T) : Bool = shouldBeKilled()
}

object ROBType extends ChiselEnum {
    val Arithmetic, Branch, Jump, Store, CSR = Value
}

// 如果实现no-amt方案，并且在branch unit实现一个简单的queue，就不需要这些东西了
class ROBContent(implicit p: Parameters) extends CustomBundle {
    val valid = Bool()
    val instr_addr = UInt(p.XLEN.W) // Instruction address
    // val mispred = Bool() // Misprediction flag(1 represents misprediction)
    val completed = Bool() // Completion flag(1 represents completion)

    // TODO: Currently the valid bits in both are the same
    val pdst = Valid(UInt(bl(p.PRF_DEPTH)))
    val rd = Valid(UInt(5.W)) // Destination register

    // Redundant, and can be merged into one, will be done in the future
    // val branch_freed = UInt(p.BRANCH_MASK_WIDTH.W)

    val is_st = Bool()

    // val is_branch = Bool()
    val ghr = UInt(p.GHR_WIDTH.W)
    val branch_taken = Bool()
    val mispred = Bool()
    val is_conditional = Bool()
    val target_PC = UInt(p.XLEN.W) // Instruction address

    // Debugging 
    val debug = new InstrDebug
    // val pdst_value = UInt(p.XLEN.W)

    
    // val ps1 = UInt(bl(p.PRF_DEPTH))
    // val ps2 = UInt(bl(p.PRF_DEPTH))
    // val ps1_value = UInt(p.XLEN.W)
    // val ps2_value = UInt(p.XLEN.W)
}

class ROBIO(implicit p: Parameters) extends CustomBundle {
    // 通过Decoupled向dispatch施压，这样实现比较正确
    // 其中外部valid表示dispatch是否正在发送
    // 内部的valid表示指令是否有效
    // 不同层级表示的意义有所不同
    val rob_uop = Flipped(Decoupled(Vec(p.CORE_WIDTH, Valid(new DISPATCH_ROB_uop()))))  //Dispatch Unit的uop,存入条目中

    // 发出下一个载入指令的地址，供Dispatch用
    val rob_index = UInt(bl(p.ROB_DEPTH) + 1.W)
    // 发出empty信号，供Dispatch使用
    val rob_empty = Bool()

    val wb_uop = Vec(p.FU_NUM, Flipped(Valid(new WB_uop()))) // 来自FUs（包括LSU）写回信息

    // 不让其他从commitsignal判断是否发生mispred，因为这是rob的职责。这里这里不是要论谁对谁错，而是画清责任的界线，这样如果内部实现方式发生了变化，就不影响其他模块，这就是api/abi的作用，目的是为了减轻工作量。
    // 写回信号，仅对特定模块起作用 
    // TO RENAME, LSU
    val rob_commitsignal = ROB.CommitSignal  //广播ROB条目

    // TO BP, and all other modules to signal reset
    val rob_controlsignal = new ROBControlSignal

    val bu_signals = Flipped(Valid(new BU_signals))
}


object CommitState extends ChiselEnum {
    val commit, flushing, restore = Value
}

// ROB采用CORE_WIDTH个BANK的好处如下：
// - 利于硬件实现
// - 容易实现
// 一般不希望fetch packet不填满，但是指令多数情况是填满的，所以一点的开销并不重要
// 另外，无需将指令左对齐，这样对硬件设计很不友好 :/
class ROB(implicit p: Parameters) extends CustomModule {
    val io = IO(new ROBIO())

    import CommitState._
    val state = RegInit(commit)

    val rob = RegInit(VecInit.tabulate(p.ROB_DEPTH)(_ => 0.U.asTypeOf(Vec(p.CORE_WIDTH, new ROBContent())))) //ROB条目

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

    val committing_entry_w = rob(whead)

    val branches = committing_entry_w.map(e => e.completed && e.valid && e.mispred)
    val has_got_branches = VecInit(branches).asUInt =/= 0.U && !empty

    val can_dequeue = !empty && committing_entry_w.map(entry => entry.completed || !entry.valid).reduce(_ && _) 

    io.rob_commitsignal := RegEnableValid(committing_entry_w, can_dequeue)
    // io.rob_controlsignal.should_flush := RegInit(false.B)
    // io.rob_controlsignal.restore_amt := RegInit(false.B)

    io.rob_controlsignal.should_flush := false.B
    io.rob_controlsignal.restore_amt := false.B
    when(state === commit && has_got_branches && can_dequeue) {
        state := flushing
        io.rob_controlsignal.should_flush := false.B
        io.rob_controlsignal.restore_amt := false.B
    }.elsewhen(state === flushing) {
        state := restore 
        io.rob_controlsignal.should_flush := true.B
        io.rob_controlsignal.restore_amt := false.B
    }.elsewhen(state === restore) {
        state := commit
        io.rob_controlsignal.should_flush := false.B
        io.rob_controlsignal.restore_amt := true.B
    }

    io.rob_uop.ready := can_enqueue && !has_got_branches

    when(can_dequeue) {
        dbg(cf"ROB dequeuing ${io.rob_uop.bits}")
        head := head + 1.U
    }

    // io.rob_controlsignal.should_flush := RegNext(has_got_branches && can_dequeue)

    // val c_branches = io.rob_commitsignal.bits.map(e => e.valid && e.mispred)
    // val c_has_got_branches = VecInit(branches).asUInt =/= 0.U && io.rob_commitsignal.valid
    // io.rob_controlsignal.restore_amt := RegNext(RegNext(has_got_branches && can_dequeue))

    when(has_got_branches) {
        tail := head + 1.U
    }
    .elsewhen(io.rob_uop.fire && !RegNext(has_got_branches && !empty)) {
        dbg(cf"Enqueuing ${io.rob_uop.bits}")
        rob(wtail) := io.rob_uop.bits.map(uop =>
            convert_to_content(uop)
        )
        tail := tail + 1.U
    }    

    when(io.bu_signals.valid) {
        val rob_index = io.bu_signals.bits.rob_index
        val rob_inner_index = io.bu_signals.bits.rob_inner_index
        val rob_entry = rob(rob_index)

        when(io.bu_signals.bits.mispred) {
            rob_entry.zipWithIndex.foreach{case (x, ind) => {
                when(ind.asUInt > rob_inner_index ) {
                    x.valid := false.B
                }
            }}
        }
        
        (rob_entry(rob_inner_index): Data).waiveAll :<>= (io.bu_signals.bits: Data).waiveAll
    }

    //分配条目的逻辑
    def convert_to_content(dis_uop: Valid[DISPATCH_ROB_uop]): ROBContent = {
        val uop = dis_uop.bits
        val rob_allocate = Wire(new ROBContent)
        rob_allocate := DontCare 
        //common部分
        (rob_allocate: Data).waiveAll :<>= (uop: Data).waiveAll
        rob_allocate.valid := dis_uop.valid
        rob_allocate.mispred := false.B
        rob_allocate.completed := false.B

        rob_allocate.is_st := uop.instr_type === InstrType.ST

        // Debugging
        rob_allocate.debug := dis_uop.bits.debug

        rob_allocate
    }

    io.wb_uop.foreach{ wb_uop =>
        // NOTE: It has an extra most significant bit
        val index = wb_uop.bits.rob_index
        // TODO
        val inner_offset = wb_uop.bits.rob_inner_index
        when(wb_uop.valid) {
            rob(index)(inner_offset).completed := true.B

            //DEbugging
            // rob(index)(inner_offset).pdst_value := wb_uop.bits.pdst_value
            // rob(index)(inner_offset).ps1_value := wb_uop.bits.ps1_value
            // rob(index)(inner_offset).ps2_value := wb_uop.bits.ps2_value
        }
    }
}

