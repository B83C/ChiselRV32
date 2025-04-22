#import "util.typ": *
#show: codly-init.with()
#show table.cell.where(x: 0): strong
#set table(
  stroke: (x, y) => if y == 0 {
    (bottom: 0.7pt + black)
  },
  align: (x, y) => (
    if x > 0 { center }
    else { left }
  )
)

= Revision History
#table(
  columns: 3,
  gutter: 3pt,
  [Author], [Description], [Date],
  [刘恒雨], [Initialised and updated spec doc], [2025/04/10],
)

#set heading(numbering: "1.1")
#outline()

= Terminology
/ PRF: Physical Register File
/ WAT: Wakeup Allocation Table
/ RMT: Register Map Table
/ SDA: Store Data Array
/ STQ: STore Queue
/ LDQ: LoaD Queue
/ LSU: LoaD Queue

= Overview
#include "diagram.typ"

= Microarchitecture
本处理器为乱序执行双发射RV32IM架构，代码设计风格主要借鉴于BOOM。
== 功能部件介绍
=== 取指 (IF)
取指单元在单周期内完成取指操作...
=== 分支预测 (BP)
=== 译码 (ID)
=== 重命名 (RU)
=== 派遣 (DP)
=== 指令发射 (IQ)
=== 重排序 (ROB)
=== 执行 (EXU)
=== 物理寄存器堆 (PRF)

Summary:
#table(
  columns: 2,
  gutter: 3pt,
  [功能], [实现方法],
  [分支预测], [BTB + GShare],
  [执行单元], [分为ALU, Branch Unit, CSR, MUL],
)
== 特别案例处理方式
=== Load Store违例
=== 精确异常以及分支误判回滚
=== CSR写入

= Units
#dpio(c.ExecutionUnit)
#dpio(c.ALU)

= Parameters
= Interface
#context interfaces_used.final()
