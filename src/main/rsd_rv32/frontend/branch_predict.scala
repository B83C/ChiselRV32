package rsd_rv32.frontend
import chisel3._
import chisel3.util._
import rsd_rv32.common._

class BP_IFU_Interface(params : Parameters) extends Bundle {
    val PC_cur = Input(UInt(params.XLEN.W)) //当前IFU的PC值
    val PC_target = Output(UInt(params.XLEN.W)) //预测的下个cycle取指的目标地址
    val BTB_Hit = Output(Vec(params.FETCH_WIDTH, Bool())) //1代表hit，0相反；将最年轻的命中BTB的置为1，其余为0
    val BHT_Taken = Output(Bool()) //branch指令的BHT的预测结果；1代表跳转，0相反
    val GHR = Output(UInt(params.GHR_WIDTH.W)) //作出预测时的全局历史寄存器快照
}

class BP_ROB_Interface(params : Parameters) extends Bundle {
    val PC = Input(UInt(params.XLEN.W)) //当前ROB的PC值
    val instrType = Input(UInt(3.W)) //当前指令类型,该模块需要区分条件分支和无条件分支
    val BTB_Hit = Input(Bool()) //该分支指令最初是否命中BTB
    val actual_Taken = Input(Bool()) //实际是否taken
    val GHR = Input(UInt(params.XLEN.W)) //作出预测时的全局历史寄存器快照，使得更新BHT时能够生成正确的index
    val actualTargetPC = Input(UInt(params.XLEN.W)) //实际跳转的目标地址
}

class BP_IO (params : Parameters) extends Bundle {
    val ifu = new BP_IFU_Interface(params)
    val rob = new BP_ROB_Interface(params)
}

class BranchPredictor(params : Parameters) extends Module {
    val io = IO(new BP_IO(params))
}
