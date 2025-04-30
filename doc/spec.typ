#import "util.typ": *

#set text(font: ("Libertinus Serif", "Noto Serif CJK SC"))
#set par(first-line-indent: (amount: 2em, all: true), justify: true, leading: 1em)
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
  columns: (2fr, 2fr, 1fr),
  gutter: 3pt,
  [Author], [Description], [Date],
  [刘恒雨], [Initialised and updated spec doc], [2025/04/10],
  [刘恒雨], [Finialised automatic code referencing logic], [2025/04/23], 
  [胡英瀚，杨钧铎，饶忠禹], [提供模块功能框图，以及功能部件阐述], [2025/04/23],
  [刘恒雨], [完善spec文档], [2025/04/30],
)

#set heading(numbering: "1.1")
#outline()
#pagebreak()

= Terminology
#show: make-glossary
#let entry-list = (
  (
    key: "kuleuven",
    short: "KU Leuven",
    long: "Katholieke Universiteit Leuven",
    description: "A university in Belgium.",
  ),
)
#register-glossary(entry-list)
#print-glossary(
 entry-list
)
/ PRF: Physical Register File
/ RMT: Register Map Table
/ RMT: Architectural Map Table
/ STQ: STore Queue
/ LDQ: LoaD Queue
/ LSU: Load Store Unit
/ EXU: Execution Unit, 是所有FU的总称
/ FU: Functional Unit, 指ALU、MUL、DIV等功能元件

= Overview
#include "diagram.typ"

= Microarchitecture
本处理器为乱序执行双发射RV32IM架构，代码设计风格部分借鉴于BOOM。
== 功能部件介绍
=== 取指 (IF)

#let inc_pc = $"PC(对齐)"+ "CORE_WIDTH" times "4"$

// 取指单元在单周期内读取CORE_WIDTH个指令，随后在一个周期检测指令组是否包含跳转指令并与分支预测结果（BP）进行比较，进而确定PC_Next，再将CORE_WIDTH个指令发送到译码单元。这CORE_WIDTH个指令将保持程序原有的执行次序，并且随带着valid位表示是否为有效指令。valid位为主要由PC地址低有效位中非对齐部分和分支预测结果决定。
取指单元在单周期内读取CORE_WIDTH个指令，随后在检测指令组是否包含跳转指令并与分支预测结果（BP）进行比较，进而确定PC_Next，同时将CORE_WIDTH个指令发送到译码单元。这CORE_WIDTH个指令将保持程序原有的执行次序，并且随带着valid位表示是否为有效指令。valid位为主要由PC地址低有效位中非对齐部分和分支预测结果决定。

PC_Next对于存储器来说永远是对齐的（把低有效位置零即可），而PC_Next本身不一定是对齐的，因此在取指后，需要将PC(非对齐)之前的指令作废（vaild = 0)。

PC_Next主要来自：ROB、分支预测(BP)、#inc_pc，其中优先级由高到低排列。当ROB发出转跳信号时（ROB退休指令提示分支预测错误），PC_Next取自ROB，否则按照BP命中与否来决定PC_Next是PC_BP 还是 #inc_pc。

此外，取指单元同样也会检测指令组是否包含CSRRW，

// PC_Inc是#inc_pc通过寄存器的输出，确保不存在组合逻辑循环。

// 为了简化分支预测逻辑，我们只将非对齐的PC发送给BP。
为了简化分支预测逻辑，我们只将PC_Next发送给BP。

#hint(c.FetchUnit)

=== 分支预测 (BP)
#let bp_full= read("bp.png", encoding: none)
#figure(image-crop(bp_full,
  crop-width: 500,
  crop-height: 250,
  crop-start-x: 125,
  crop-start-y: 85
), caption: "BHT结构：采用分开的T/NT策略，以PC部分低位作为选择器索引") <bht>
#figure(image-crop(bp_full,
  crop-width: 550,
  crop-height: 180,
  crop-start-x: 125,
  crop-start-y: 350
), caption: "BTB条目") <btb>

// 对于BHT，PC_Next将与GHR通过Hash函数生成T和NT表的索引，同时截取一部分PC地址
我们省却了RAS，并对跳转和分支指令使用共同的BTB表，表中以isConditional区分两者。若BTB命中且isConditional为假，则跳转结果为T（覆盖BHT结果）。

分支跳转指令退休时，ROB会发出信号更新BTB、BHT表以及GHR。

为了避免组合逻辑循环，BHT预测结果和BTB输出必须是寄存器输出。

#hint(c.BranchPredictorUnit)

=== 译码 (ID)
从取指单元接受CORE_WIDTH个指令，并对有效的指令进行译码操作，最后发送到重命名单元。

译码单元不会造成流水线堵塞，因此只传递后级的阻塞信号。

#hint(c.DecodeUnit)

=== 重命名 (RU)
#figure(image-crop(bp_full,
  crop-width: 550,
  crop-height: 550,
  crop-start-x: 125,
  crop-start-y: 550,
  width: 70%
), caption: "重命名架构") <ru>
从译码单元接受CORE_WIDTH个指令，执行时必须确保CORE_WIDTH个指令能够同时被重命名（不包括无效指令），否则阻塞流水线。

对指令源寄存器重命名时分别从RMT和AMT获得对应的映射关系。其中RMT优先级高于AMT，亦即当RMT中的映射关系被记为有效时，应当选取RMT，否则AMT取胜。RMT中的有效位只有在回滚时被清零，目的是为了减少冗余的复制操作。

对于目的寄存器，需从PRF Freelist获取CORE_WIDTH个空闲物理寄存器，获取成功后将index写入RMT，否则stall。

#hint(c.RenameUnit)

=== 派遣 (DP)
从重命名单元接受CORE_WIDTH个指令，执行时必须确保CORE_WIDTH个指令能够同时被派遣，否则阻塞流水线。

此单元将根据uop的类型，将指令分别派遣到exu_issue, ld_issue, st_issue等指令发射队列。

#hint(c.DispatchUnit)

=== 指令发射 (IQ)
#[
#show regex("[A-z]+_issue") : it => [*#lower(it)*]
#figure(image("dispatch_issue_birds_eye_view.png"), caption: "Issue Queue 工作原理图") <iq>
#figure(image(width: 50%, "iq.jpeg"), caption: "Issue Queue 条目") <iq_entry>
Issue queue用于向各个EXU发射命令，需要监听后续EXU是否空闲以及各操作数的就绪状态。操作数设置为就绪有三种途径：

#set enum(indent: 2em)
+ 监听PRF 中各物理寄存器的Valid信号
+ 监听EXU处的ready信号
+ 监听EXU后的级间寄存器的ready信号

在本CPU中，IQ分为三部分，分别是exu_issue, ld_issue, st_issue，其中后面二者间有数据传输，可见后续说明。

exu_issue的运行过程如下，在非满时接收dispatch模块的uop存入payloadRAM中，将有用信息（instrType、psrc）存入queue中用于判断就绪状态，等待发射，每周期根据条目就绪状态至多选择2条已就绪的指令发射给FU（内含两个ALU、一个BU、一个乘法器、一个除法器），同时向dispatch模块发送本周期发出的指令的IQ地址，用以更新IQ Free List（位于dispatch单元）。

St_issue基本与exu_issue相同，向store流水线（位于LSU中）发送uop，不同的是要向ld_issue发送每周期仍未发射的条目信息。Ld_issue需要从st_issue接收信息的目的在于解决load-store违例。

在本CPU中，store指令走完store流水线的周期固定为2，而load指令在load流水线第二级才会从stq获取前馈数据(见@lsu)，所以只需要保证在程序顺序上位于该load指令之前的store指令全部进入STU后再发射load，就可以避免load指令越过有数据依赖的store指令，错误地读取数据（store – load violation）。为此，需要一个存储依赖关系的矩阵，load指令在进入ld_issue时，就要接收当前周期st_issue中尚未发射的store条目，以二进制数(位宽为ST_ISSUE_QUEUE_DEPTH)的形式存入矩阵的一行中。每当st_issue发射一条store指令，就要更新ld_issue矩阵中对应的列，将其全部置为0表示就绪。当一条load命令所在行全为0时，表示与其相关的（比该load指令更年轻的）store指令已经全部发射，该load指令解除限制（又若load指令的psrc为ready状态，则该load指令具备发射条件）。
]

#hint(c.exu_issue_queue, c.ld_issue_queue, c.st_issue_queue)

=== 重排序 (ROB)
Reorder Buffer(ROB)，是一个FIFO结构，用于按照程序序存储指令，可以实现顺序retire，从而实现分支预测失误后的回滚与精确异常（本项目无需实现精确异常）

在实际工作时，ROB从dispatch接收uop并储存，把自身头尾指针以及empty/full标志传递给dispatch单元。从各个EXU接收指令完成信号，更新complete状态。ROB总是广播位于头部的两条指令，用额外的valid位（两条指令各有一个）来指示本周期是否retire该指令，用于AMT的更新、通知STQ可以写入内存、通知其他模块发生分支预测错误。

#hint(c.ROB)

=== 寄存器访问和旁路 (RRDWB)
指令被发射后会先通过寄存器访问和旁路单元(RRDWB)获得操作数。此时，若旁路有数据传回，则在寄存器写入地址（pdest）与当前指令psrc相同的条件下载入传回数据，否则载入PRF数据。功能单元的每一个寄存器读端口均有一个RRDWB单元。

#hint(c.BypassNetwork)

=== 功能单元 (FU) <FU>
FU为ALU, BU, MUL, DIV, CSR的抽象类，主要提供基本的输出入输出端口。指定单元（BU, CSR等）有额外读写端口专门与ROB交互。

==== ALU 
主要实现加减法，逻辑和算数位移操作，可流水化实现，不阻塞流水线。
==== BU 
内部实现减法器，检测分支跳转预测结果是否正确。通过额外的端口将跳转信息写入ROB。
==== MULFU
多周期整数乘法流水线
==== DIVFU
多周期整数除法流水线
==== CSR <csr_mitigation>
由于写入CSR寄存器时会更改处理器的状态，所以我们按照一般做法：ID在检测到CSRRW指令后，阻塞流水线直到ROB队首执行CSRRW为止。与LSU类似，在ROB队首遇到了CSRRW才执行指令。 

CSR寄存器通过Memory mapping把地址映射到特定的寄存器（可以是外设寄存器，或是io端口）。本处理器只实现简单的mcycle寄存器，实现方法是额外设立一个计数器，在csr读写时，使能读写端口。

#hint(c.FunctionalUnit, c.ALUFU, c.BranchFU, c.MULFU, c.DIVFU, c.CSRFU)

// === 执行 (EXU)
// 执行单元（EXU）指多个FU以及LSU的组合。一个Issue Queue可以有多于一个Exu，但本项目只考虑一个的情况。

==== FU组合 

见 @FU
// #hint(c.FunctionalUnit)

==== LSU
#figure(image("lsu_internals.png"), caption: "LSU 工作原理图") <lsu>


#hint(c.LSU)

=== 物理寄存器堆 (PRF)
物理寄存器堆存有PRF_DEPTH个寄存器，寄存器宽度为XLEN。由于存在多个FU，PRF必须有多个读写端口。

#hint(c.PRF)

Summary:
#table(
  columns: (1fr, 1fr),
  gutter: 3pt,
  [功能], [实现方法],
  [分支预测], [BTB + BHT(T/NT)],
  [重命名表], [AMT+RMT, RMT存有有效位，无效时以AMT为准],
  [发射队列], [一个执行单元对应一个发射队列],
  [执行单元], [两个执行单元，即FU（ALU, BU, MUL, DIV, CSR）的组合和LSU],
  [Cache], [无],
)
== 特别案例处理方式
=== Load Store违例 (Load Store Violation)
#[
#set list(indent: 2em)
Load Store违例是当发射队列中操作地址相同且较年轻的load指令比老的store指令更优先执行。其中主要原因可能是：
- Load指令的源寄存器较Store指令更早就绪
为简便起见，我们采用分开的load和store发射FIFO队列，同时维护一个依赖矩阵，用以保存load-store之间的年龄信息。只有load之前的所有store执行完毕，load指令才能执行。该矩阵的每一行对应一个load指令，每一列对应store指令。每当load载入issue queue时，对应行将载入store queue的快照；store指令在完成之后，将对应列清零。由此，当且仅当load的一行全为零才符合被执行的条件。
  
]
=== 分支误判回滚
分支预测在判定为错误后，由BU写入ROB。直到当该分支指令退休时，才对系统进行回滚操作。回滚时，RMT每一行的vaild bit清零，所有freelist以及rob头部指针与尾部指针重合，同时将跳转地址发送至IF并重置所有其它模块。
=== CSR写入
见@csr_mitigation


= Units
#modules

= Parameters
// #p(c.Parameters)

= Interface
#interfaces
