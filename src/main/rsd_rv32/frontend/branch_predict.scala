class BP_IFU_Interface(val fetch_width : Int , val GHR_width : Int) extends Bundle {
    val PC_curr = Input(UInt(32.W)) //当前IFU的PC值
    val PC_target = Output(Vec(fetch_width, UInt(32.W))) //预测的目标地址
    val BTB_Hit = Output(Vec(fetch_width, Bool())) //是否命中BTB
    val BHT_Taken = Output(Vec(fetch_width, Bool())) //是否预测为taken
    val GHR = Output(UInt(GHR_width.W)) //作出预测时的全局历史寄存器快照，使得更新BHT时能够生成正确的index
}

class BP_ROB_Interface(val GHR_width : Int) extends Bundle {
    val PC = Input(UInt(32.W)) //当前ROB的PC值
    val instrType = Input(UInt(2.W)) //当前指令类型,该模块需要区分条件分支和无条件分支
    val BTB_Hit = Input(Bool()) //是否命中BTB。根据条件分支最初是否命中BTB，给出不同的更新BHT策略
    val predict_Taken = Input(Bool()) //是否预测为taken
    val actual_Taken = Input(Bool()) //实际是否taken
    val GHR = Input(UInt(GHR_width.W)) //作出预测时的全局历史寄存器快照，使得更新BHT时能够生成正确的index
    val actualTargetPC = Input(UInt(32.W)) //实际跳转的目标地址
}