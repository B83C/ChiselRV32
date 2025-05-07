package rsd_rv32.execution
import scala.collection.mutable.ArrayBuffer

import rsd_rv32.common._
import chisel3._
import chisel3.util._

// abstract class RegisterFile[T <: Data](
//   dType: T,               // 寄存器存储的数据类型
//   numRegisters: Int,       // 物理寄存器数量（x0-x31对应物理寄存器号）
//   numReadPorts: Int,       // 并行读端口数（双发射需要至少2读）
//   numWritePorts: Int)      // 并行写端口数（双发射需要至少2写）
//   (implicit p: Parameters) extends Module // 继承BOOM基础模块
// {
//   val io = IO(new Bundle {
//     // 读请求端口（Decoupled接口实现流控）
//     val read_requests = Vec(numReadPorts, Flipped(Decoupled(
//       UInt(log2Ceil(numRegisters).W) // 地址位宽=log2(寄存器数)
//     )))

//     // 读响应数据输出（对齐流水线级延迟）
//     val read_data = Vec(numReadPorts, Output(dType))

//     // 写端口数组（Valid接口保证写时序）
//     val write_ports = Vec(numWritePorts, Flipped(Valid(new Bundle {
//       val addr = Output(UInt(log2Ceil(numRegisters).W)) // 最大物理寄存器地址位宽
//       val data = Output(dType)            // 写入数据
//     })))
//   })

//   // 写端口冲突检测断言（确保同一周期不同时写同一寄存器）
//   if (numWritePorts > 1) {
//     for (i <- 0 until (numWritePorts - 1)) {
//       for (j <- (i + 1) until numWritePorts) {
//         // 当两个写端口同时有效时，检查地址是否冲突
//         assert(
//           !io.write_ports(i).valid ||        // 写端口i无效
//           !io.write_ports(j).valid ||        // 或写端口j无效
//           (io.write_ports(i).bits.addr =/= io.write_ports(j).bits.addr), // 或地址不同
//           "[regfile] 检测到多个写端口同时写入同一寄存器！"
//         )
//       }
//     }
//   }
// }

// class BankedRF[T <: Data](
//   dType: T,
//   numBanks: Int,                          // 分块数（典型值为4）
//   numLogicalReadPortsPerBank: Int,         // 每块逻辑读端口数
//   numRegisters: Int,                       // 总物理寄存器数
//   numLogicalReadPorts: Int,                // 逻辑读端口总数
//   numPhysicalReadPorts: Int,               // 物理读端口数（>=逻辑数）
//   numWritePorts: Int,                      // 写端口数（双发射为2）
//   bankedWritePortArray: Seq[Option[Int]],  // 写端口分块映射（如Seq(Some(0), None)）
//   typeStr: String)                         // 类型标识用于调试
// (implicit p: Parameters)
//     extends RegisterFile(                  // 继承抽象寄存器文件
//       dType, numRegisters, numLogicalReadPorts, numWritePorts)
// {
//   // 实际寄存器存储实现（SyncReadMem生成同步读存储器）
//   val regfile = SyncReadMem(numRegisters, dType)

//   // 写端口连接逻辑
//   io.write_ports.zipWithIndex.foreach { case (wp, i) =>
//     when(wp.valid) {
//       // 将数据写入对应地址（SyncReadMem的写是立即生效的）
//       regfile.write(wp.bits.addr, wp.bits.data)
//     }
//   }

//   // 读端口处理逻辑（分块仲裁）
//   io.arb_read_reqs.zip(io.rrd_read_resps).foreach { case (req, resp) =>
//     // 默认输出0（x0寄存器硬连线）
//     resp := 0.U.asTypeOf(dType)
//     when(req.ready && req.valid) {
//       // 同步读取寄存器值（1周期延迟）
//       resp := regfile.read(req.bits)
//     }
//   }

//   // 分块冲突检测逻辑（示例）
//   require(numBanks > 0, "分块数必须大于0")
//   bankedWritePortArray.foreach { 
//     case Some(bank) => 
//       require(bank < numBanks, s"写端口映射到不存在的分块$bank")
//     case None => // 允许全局写
//   }
// }
// // 确保每个寄存器同一周期只有一个写入者（x0寄存器除外）
// if (numWritePorts > 1) {
//   for (i <- 0 until (numWritePorts - 1)) {
//     for (j <- (i + 1) until numWritePorts) {
//       assert(!io.write_ports(i).valid ||        // 写端口i无效时通过
//              !io.write_ports(j).valid ||        // 或写端口j无效时通过
//              (io.write_ports(i).bits.addr =/= io.write_ports(j).bits.addr), // 或地址不同时通过
//         "[regfile] 同一寄存器被多个写端口同时写入！" // 断言错误提示
//     }
//   }
// }


// class BankedRF[...](...)
//     extends RegisterFile(...) 
// {
//   // 前置条件校验
//   require(isPow2(numBanks))                     // 分块数必须是2的幂
//   require(numRegisters % numBanks == 0)         // 寄存器总数必须能被分块数整除
//   require(bankedWritePortArray.length == numWritePorts) // 写端口配置数组长度需匹配
  
//   // 计算专用写端口数量（配置为Some的端口）
//   val numDedicatedWritePorts = bankedWritePortArray.flatten.length 
  
//   // 计算每个分块的写端口数
//   val writePortsPerBank = if (numDedicatedWritePorts == 0) {
//     numWritePorts                               // 无专用端口时均匀分配
//   } else {
//     numWritePorts - numDedicatedWritePorts + 1  // 专用端口占用独立资源
//   }

//   // 计算地址对应的分块索引（取低位）
//   def bankIdx(i: UInt): UInt = i(log2Ceil(numBanks)-1,0)

//   // 实例化各分块的子寄存器文件
//   val rfs = (0 until numBanks) map { w => Module(new PartiallyPortedRF(
//     dType,
//     numRegisters / numBanks,                    // 每个分块的寄存器数
//     numLogicalReadPortsPerBank,                 // 每分块逻辑读端口
//     numPhysicalReadPorts,                       // 物理读端口（实际硬件端口）
//     writePortsPerBank,                          // 每个分块的写端口数
//     typeStr + s" Bank ${w}"                     // 调试标识
//   )) }

//   // 单分块特殊处理
//   if (numBanks == 1) {
//     require(numLogicalReadPortsPerBank == numLogicalReadPorts)
//     io <> rfs(0).io                             // 直接连接IO
//   } else {
//     // 分块写端口映射
//     val widxs = Array.fill(numBanks)(0)         // 跟踪各分块当前写端口索引
//     for (i <- 0 until numWritePorts) {          // 遍历所有写端口
//       if (bankedWritePortArray(i) != None) {    // 处理专用写端口
//         val bank = bankedWritePortArray(i).get
//         val widx = widxs(bank)
//         // 连接有效信号
//         rfs(bank).io.write_ports(widx).valid     := io.write_ports(i).valid
//         // 地址转换：全局地址右移得到分块内地址
//         rfs(bank).io.write_ports(widx).bits.addr := io.write_ports(i).bits.addr >> log2Ceil(numBanks)
//         // 数据直连
//         rfs(bank).io.write_ports(widx).bits.data := io.write_ports(i).bits.data
//         // 验证地址匹配分块
//         assert(!io.write_ports(i).valid || bankIdx(io.write_ports(i).bits.addr) === bank.U)
//         widxs(bank) = widx + 1                  // 更新分块写端口索引
//       } else {                                  // 处理全局写端口
//         for (w <- 0 until numBanks) {           // 广播到所有分块
//           val widx = widxs(w)
//           // 条件有效：地址属于当前分块时才有效
//           rfs(w).io.write_ports(widx).valid     := io.write_ports(i).valid && 
//             bankIdx(io.write_ports(i).bits.addr) === w.U
//           // 地址转换同上
//           rfs(w).io.write_ports(widx).bits.addr := io.write_ports(i).bits.addr >> log2Ceil(numBanks)
//           rfs(w).io.write_ports(widx).bits.data := io.write_ports(i).bits.data
//           widxs(w) = widx + 1
//         }
//       }
//     }
//     // 验证写端口分配正确
//     require(widxs.forall(_ == writePortsPerBank), widxs.mkString(","))

//     // 读端口处理（分块仲裁）
//     if (numLogicalReadPortsPerBank == numLogicalReadPorts) {
//       for (i <- 0 until numLogicalReadPorts) {   // 遍历每个读端口
//         val bidx = bankIdx(io.arb_read_reqs(i).bits) // 获取目标分块
//         for (w <- 0 until numBanks) {            // 连接所有分块
//           // 有效信号：仅当请求属于当前分块
//           rfs(w).io.arb_read_reqs(i).valid := io.arb_read_reqs(i).valid && 
//             (bankIdx(io.arb_read_reqs(i).bits) === w.U)
//           // 地址转换：去除分块索引位
//           rfs(w).io.arb_read_reqs(i).bits  := io.arb_read_reqs(i).bits >> log2Ceil(numBanks)
//         }
//         // 生成仲裁选择信号（One-Hot编码）
//         val arb_data_sel = UIntToOH(bidx)
//         // 延迟选择信号对齐流水线
//         val rrd_data_sel = RegNext(arb_data_sel)
//         // Ready信号选择：根据仲裁结果选择对应分块的Ready
//         io.arb_read_reqs(i).ready := Mux1H(arb_data_sel, rfs.map(_.io.arb_read_reqs(i).ready))
//         // 读响应数据选择：使用寄存后的选择信号
//         io.rrd_read_resps(i)      := Mux1H(rrd_data_sel, rfs.map(_.io.rrd_read_resps(i)))
//       }
//     }
//   }
// }


// class PartiallyPortedRF[...](...)
//     extends RegisterFile(...)
// {
//   // 实例化全端口寄存器文件
//   val rf = Module(new FullyPortedRF(...))
//   rf.io.write_ports := io.write_ports  // 直连写端口

//   // 端口仲裁状态跟踪
//   val port_issued = Array.fill(numPhysicalReadPorts) { false.B } // 端口占用标记
//   val port_addrs  = Array.fill(numPhysicalReadPorts) { 0.U }     // 端口当前地址
//   val data_sels   = Wire(Vec(numLogicalReadPorts , UInt(numPhysicalReadPorts.W))) // 数据选择信号

//   // 逻辑读端口到物理端口的映射
//   for (i <- 0 until numLogicalReadPorts) {  // 遍历每个逻辑读端口
//     var read_issued = false.B
//     for (j <- 0 until numPhysicalReadPorts) { // 尝试寻找空闲物理端口
//       val issue_read = WireInit(false.B)
//       val use_port = WireInit(false.B)
//       // 当逻辑端口有效且物理端口空闲时分配
//       when (!read_issued && !port_issued(j) && io.arb_read_reqs(i).valid) {
//         issue_read := true.B
//         use_port := true.B
//         data_sels(i) := UIntToOH(j.U) // 生成选择信号
//       }
//       // 更新端口占用状态
//       val was_port_issued_yet = port_issued(j)
//       port_issued(j) = use_port || port_issued(j)
//       // 捕获当前请求地址
//       port_addrs(j) = port_addrs(j) | Mux(was_port_issued_yet || !use_port, 0.U, io.arb_read_reqs(i).bits)
//       read_issued = issue_read || read_issued
//     }
//     // Ready信号生成：有空闲物理端口时有效
//     io.arb_read_reqs(i).ready := PopCount(io.arb_read_reqs.take(i).map(_.valid)) < numPhysicalReadPorts.U
//     // 验证请求必定被处理
//     assert(!(io.arb_read_reqs(i).fire && !read_issued))
//   }

//   // 连接物理读端口
//   for (j <- 0 until numPhysicalReadPorts) {
//     rf.io.arb_read_reqs(j).valid := port_issued(j) // 有效信号
//     rf.io.arb_read_reqs(j).bits  := port_addrs(j)  // 地址输入
//     assert(rf.io.arb_read_reqs(j).ready)           // 确保全端口设计总是就绪
//   }

//   // 流水线对齐：延迟选择信号
//   val rrd_data_sels = RegNext(data_sels)

//   // 输出数据选择
//   for (i <- 0 until numLogicalReadPorts) {
//     // 根据延迟后的选择信号选择物理端口数据
//     io.rrd_read_resps(i) := Mux1H(rrd_data_sels(i).asBools, rf.io.rrd_read_resps)
//   }
// }


// class FullyPortedRF[T <: Data](
//   dType: T,               // 寄存器存储的数据类型（如32位整数）
//   numRegisters: Int,       // 物理寄存器数量（包含x0-x31）
//   numReadPorts: Int,       // 并行读端口数（典型双发射需要2读）
//   numWritePorts: Int,      // 并行写端口数（典型双发射需要2写）
// )(implicit p: Parameters)
//     extends RegisterFile(dType, numRegisters, numReadPorts, numWritePorts)
// {
//   // 计算寄存器文件的硬件成本指标（用于设计空间探索）
//   // 公式：(R+W)*(R+2W) 反映面积和功耗的估算模型
//   val rf_cost = (numReadPorts + numWritePorts) * (numReadPorts + 2*numWritePorts)

//   // 读端口就绪信号处理（全端口设计无需背压）
//   io.arb_read_reqs.map(p => p.ready := true.B)  // 所有读请求立即就绪

//   // 实例化实际的寄存器存储（使用Chisel的Mem生成组合读/同步写存储器）
//   val regfile = Mem(numRegisters, dType)  // 参数1：深度，参数2：数据类型

//   // 读端口处理逻辑（组合逻辑读）
//   (0 until numReadPorts) map { p => 
//     // 寄存器读操作（RegNext用于对齐流水线时序）
//     // 注意：Mem的读是组合逻辑，这里用RegNext添加一级流水寄存器
//     io.rrd_read_resps(p) := regfile(RegNext(io.arb_read_reqs(p).bits)) 
//   }

//   // 写端口处理逻辑（同步写）
//   io.write_ports map { p => 
//     when (p.valid) {  // 当写使能有效时
//       // 将数据写入指定地址（Mem的写操作是同步的，在时钟上升沿生效）
//       regfile(p.bits.addr) := p.bits.data 
//       // 注意：此处未处理x0寄存器写保护，需在更高层级处理
//     }
//   }
// }

/* 多端口读写物理寄存器*/
class PRF[T<: Data](
  dType: T,
  regDepth: Int,
  numReadPorts: Int,
  numWritePorts: Int,
)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val read_requests = Vec(numReadPorts, Flipped(Decoupled( // 读请求端口（Decoupled接口实现流控）
      UInt(log2Ceil(regDepth).W) // 地址位宽=log2(寄存器数)
    )))

    val read_data = Vec(numReadPorts, Output(dType)) // 读响应数据输出（对齐流水线级延迟）

    val write_ports = Vec(numWritePorts, Flipped(Valid(new Bundle { // 写端口数组（Valid接口保证写时序）
      val addr = Output(UInt(log2Ceil(numRegisters).W)) // 最大物理寄存器地址位宽
      val data = Output(dType)            // 写入数据
    })))
  })
}
