import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

class ALUIO(implicit p: Parameters) extends Bundle {
  val in1 = Input(UInt(p(XLen).W))  
  val in2 = Input(UInt(p(XLen).W))  
  val fn  = Input(UInt(4.W))        // 运算类型 (ADD/SUB/AND/OR/XOR等)

  // 输出结果
  val out = Output(UInt(p(XLen).W)) // 运算结果
  val cmp_out = Output(Bool())      // 比较结果（用于分支指令）
}

class ALU(implicit p: Parameters) extends Module {
  val io = IO(new ALUIO)

  //运算逻辑
  io.out := MuxLookup(io.fn, 0.U, Seq(
    ALU_ADD -> (io.in1 + io.in2),
    ALU_SUB -> (io.in1 - io.in2),
    ALU_AND -> (io.in1 & io.in2),
    ALU_OR  -> (io.in1 | io.in2),
    ALU_XOR -> (io.in1 ^ io.in2)
  ))

  //比较逻辑
  io.cmp_out := MuxLookup(io.fn, false.B, Seq(
    ALU_EQ  -> (io.in1 === io.in2),
    ALU_NE  -> (io.in1 =/= io.in2),
    ALU_LT  -> (io.in1.asSInt < io.in2.asSInt),
    ALU_GE  -> (io.in1.asSInt >= io.in2.asSInt)
  ))
}
