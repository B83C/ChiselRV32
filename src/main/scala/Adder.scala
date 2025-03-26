import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

//基于触发器实现的加法器，运算时间最长bits个单位
class Adder(bits: Int) extends Module {
  val io = IO(new Bundle {
    val add = Input(Bool())
    val carry_in = Input(Bool())
    val a = Input(UInt(bits.W))
    val b = Input(UInt(bits.W))

    val out = Output(UInt(bits.W))
    val busy = Output(Bool())
    val carry = Output(Bool())
  })

  val reg_A = RegInit(0.U(bits.W))
  val reg_B = RegInit(0.U(bits.W))

  val comb_A = Mux(io.add & !io.busy, io.a, reg_A)
  val comb_b = Mux(io.add & !io.busy, io.b, reg_B)

  val R_0 = comb_A & comb_b
  val R_1 = comb_A | comb_b
  when(io.busy || (io.add && !io.busy)) {
    reg_A := R_0 << 1.U | (io.carry_in && !io.busy)
    reg_B := ~R_0 & R_1
  }

  io.out := reg_B
  io.busy := !(reg_A(bits - 1, 0) === 0.U)
  io.carry := io.a(bits - 1) && io.b(bits - 1)
}

//基于组合逻辑进行运算的加法操作，同上，但以空间换取时间
class AdderComb(bits: Int) extends Module {
  val io = IO(new Bundle {
    val a = Input(UInt(bits.W))
    val carry_in = Input(Bool())
    val b = Input(UInt(bits.W))
    val out = Output(UInt(bits.W))
    val carry = Output(Bool())
  })
  io.out := (0 until bits - 1).foldLeft((io.a, io.b))({ case ((comb_A, comb_B), i) => {
      val R_0 = comb_A & comb_B
      val R_1 = comb_A | comb_B
      val carry = if(i == 0) { io.carry_in } else {0.U}
      val A_next = R_0 << 1.U | carry
      val B_next = ~R_0 & R_1
      (A_next, B_next)
    }
  })._2
  io.carry := io.a(bits - 1) && io.b(bits - 1)
}

object Adder extends App {
  ChiselStage.emitSystemVerilogFile(
    new Adder(32),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}

object AdderComb extends App {
  ChiselStage.emitSystemVerilogFile(
    new AdderComb(32),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
