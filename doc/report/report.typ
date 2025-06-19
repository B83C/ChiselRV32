#import "./imports.typ": *

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

#grid(
  columns: 1,
  rows:(1fr, 1fr, 1fr),
  align: center + horizon,
  image(width: 95%, "image1.jpeg"),
  image(width: 45%, "image2.jpeg"),
  text(size: 3em)[
    嵌入式荣誉课

    第四组
  ]
)

#pagebreak()
#set heading(numbering: "1.1")
#outline(title: [目录])
#pagebreak()

= 整体架构介绍
#include "./diagram.typ"

本处理器为乱序多发射架构，支持RV32IM指令集以及Zicsr指令拓展集。流水线深度为8级以上：取指单元3级、译码1级、重命名单元1级、发射单元2级（包括dispatch）、执行单元若干级，以及写回1级。

该处理器取指、译码以及重命名宽度均为CORE_WIDTH。同样，ROB分为CORE_WIDTH个bank，派遣单元一周期载入CORE_WIDTH条指令，因此退休宽度也是CORE_WIDTH。

指令通过派遣单元鉴别指令类型后，便会分别发送到三个发射单元之一。若指令组包含csr指令，则阻塞流水线，直至ROB清空后，*顺序*发射若干个CSR指令，发射完后返回乱序执行模式。

指令在写回后会更新发射队列的信息，而由于发射队列与FU之间相隔一层寄存器，因此寄存器数据读取和写回是错开的。加之本处理器不考虑执行单元对PRF的读写端口，因此整体设计无需数据反馈通路。

转跳指令经BU处理后会马上更新处理器的状态。此时，若指令显示错误预测，则整个流水线都会被冲刷，除了当前没有被branch mask掩盖的那些指令。由于转跳指令是乱序执行的，所以必须有一个branch mask单元判断转跳指令的年龄。若指令被确定是错误判断，且不是最年长的指令，则branch mask会将其保留到内部的寄存器。直到下一个转跳指令被执行后，才会被重新检查。只有最年长的branch mask执行完毕后才能确定转跳信息（如转跳地址以及GHR）

= 前端介绍
== 取指 (IF)

#let inc_pc = $"PC(对齐)"+ "CORE_WIDTH" times "4"$

// 取指单元在单周期内读取CORE_WIDTH个指令，随后在一个周期检测指令组是否包含跳转指令并与分支预测结果（BP）进行比较，进而确定PC_Next，再将CORE_WIDTH个指令发送到译码单元。这CORE_WIDTH个指令将保持程序原有的执行次序，并且随带着valid位表示是否为有效指令。valid位为主要由PC地址低有效位中非对齐部分和分支预测结果决定。
取指单元在单周期内读取CORE_WIDTH个指令，随后在检测指令组是否包含跳转指令并与分支预测结果（BP）进行比较，进而确定PC_Next，同时将CORE_WIDTH个指令发送到译码单元。这CORE_WIDTH个指令将保持程序原有的执行次序，并且随带着valid位表示是否为有效指令。valid位主要由PC地址低有效位中非对齐部分和分支预测结果决定。同时，取指单元会从branch_mask获得当前分支掩码，并载入微指令，用于后续回滚时清除指令。

PC_Next对于存储器来说永远是（按CORE_WIDTH条指令）对齐的（把低有效位置零即可），而PC_Next本身不一定是对齐的，因此在取指后，需要将PC(非对齐)之前的指令作废（vaild = 0)。分支预测单元(BP)也将基于PC_Next判断指令组那些指令为有效，并结合预测结果，发送转跳信息给取指单元。

PC_Next主要来自：branch_mask单元、分支预测(BP)、#inc_pc，其中优先级由高到低排列。当发生分支预测错误时，branch_mask单元会发送bu_commit信号，而该信号包含正确转调地址。随后，取指单元将跟新pc_reg并进入预取指模式，等待指令到达，否则按照BP命中与否来决定PC_Next是PC_BP 还是 #inc_pc。

在处理BTB命中时，本处理器中的IF模块只接收第一条命中的指令，并用inst_mask_btb屏蔽后续指令。因为考虑即使当前IF返回了多条指令，但若第一条是跳转指令，后续指令都不会起效。在这一阶段，分支预测器的状态（GHR、是否跳转等）会被存入寄存器，并确保与memory对齐时序。在取指后，该pc前的指令会被屏蔽，避免误处理前一个fetch packet的旧指令。
再然后，IF模块会对所取指令的opcode进行初步处理，判断其是否为分支指令（bne,beq,etc..）或是jal与jalr。当所取指令是上述类别时，将跳转预测结果更新至GHR中，并将valid指示调整为1。

仅当当前fetch packet中所有有效的分支指令都能从mask freelist中申请到分支资源时，开始构建uop向量。IF模块通过遍历fetch packet中的每条指令，生成对应的uop，包含指令及其地址、分支预测结果，以及Branch mask的相关信息。在构建时，uop向量内的指令、指令地址取决于当前的PC地址。分支预测信息用BP模块提供的信息填充。Uop的有效性决定于指令本身的合法性、BTB是否屏蔽此条指令、core是否正在运行，以及（若是）分支指令是否分配了分支ID。

随后，IF模块与Branch mask模块配合，标记指令的控制流历史，以确保flush的时候不会出错。Branch mask模块会通知IF是否有Branch mask被释放，如有则把信息写入到指令uop中，这样做确保了当前指令不会重用提前被释放的branch id直到当前指令退休。

最终，IF将整个uop向量存入寄存器传给ID阶段进行后续译码操作，并保持在运行状态，等待下一组指令的处理。

== 分支预测 (BP)
#let bp_full= read("./bp.png", encoding: none)
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


在本次处理器设计中，Branch Predictor（BP）模块负责预测分支指令的行为，以提升指令流水线的效率。BP模块由多个子模块构成，包括Branch History Table（BHT）、Branch Target Buffer（BTB）、Global History Register（GHR）以及与BranchMask单元的接口。

 === Global History Register（GHR）

GHR是一个移位寄存器，用于记录最近分支指令的历史结果（taken或not taken）。在多发射处理器中，GHR的更新粒度基于fetch packet的大小，即每次更新对应一组p.CORE_WIDTH宽度的指令。这种设计简化了GHR的更新逻辑，使其能够一次性处理多个指令的历史记录。

更新机制：当BranchMask模块发出should_commit信号时，GHR会根据提供的分支结果进行更新，记录当前fetch packet的分支历史。

回滚机制：在分支预测错误时，BranchMask模块提供之前的GHR快照，BP模块将GHR回滚至该快照状态，以恢复正确的预测上下文。

=== Branch History Table（BHT）

BHT用于存储分支指令的历史行为模式，包含两个表：T_table（taken表）和NT_table（not taken表），以及一个选择器表choice_table，用于在两者间选择。

表结构：T_table和NT_table采用饱和计数器设计，记录分支指令的taken或not taken倾向。choice_table则通过计数器决定预测时使用哪个表。

预测逻辑：在取指阶段，BP模块根据当前PC和GHR的状态，从BHT中读取预测结果，决定是否跳转。

=== Branch Target Buffer（BTB）

BTB是一个高速缓存，用于存储分支指令的目标地址，提升分支预测的准确性和速度。

条目结构：每个BTB条目包含有效位、目标地址、是否为条件分支以及PC的高位标签。

命中逻辑：在取指时，BP模块检查BTB中是否存在与当前PC匹配的条目。若命中且预测为taken，则使用BTB中的目标地址作为下一个PC。

=== 与BranchMask单元的接口

BranchMask单元负责管理分支指令的掩码，并通过should_commit信号触发BP模块的更新和恢复操作。

更新信号：当BranchMask发出should_commit信号时，BP模块更新GHR、BHT和BTB，确保预测器与实际执行结果一致。

回滚机制：在预测错误时，BranchMask提供GHR快照，BP模块据此回滚GHR并通知流水线冲刷错误指令。

=== 多发射支持

BP模块支持多发射处理器，能够同时为fetch packet中的多个指令进行预测。

并行预测：每个时钟周期，BP模块检查fetch packet中每条指令的PC是否命中BTB，并根据BHT结果预测是否跳转。

优先级逻辑：若多个分支指令命中BTB，BP模块选择第一个预测为taken的分支，并使用其目标地址作为下一个PC。

=== 更新逻辑

BP模块在分支指令执行后更新预测器，确保其准确性。

BHT更新：根据分支指令的实际结果（taken或not taken），更新T_table或NT_table中的饱和计数器，并调整choice_table的选择倾向。

BTB更新：若分支指令为新指令或目标地址发生变化，更新BTB条目，包括目标地址和条件分支标记。

=== 恢复机制

分支预测错误时，BP模块通过以下步骤快速恢复：

GHR回滚：利用BranchMask提供的快照bu_signal信号将GHR恢复至错误预测前的状态。

流水线冲刷：通知流水线冲刷错误的指令，并从正确的PC重新取指。

== 译码 (ID)
译码单元从取指单元接受CORE_WIDTH个指令，并对有效的指令进行译码操作，最后发送到重命名单元。若整体无效，则不发射指令组。


ID模块把指令分为了MUL,DIV,ALU,LD,ST,Branch等类别。根据指令的操作码opcode解析出指令的类型，从而确定操作数在指令中所在的位置，随后解析出指令操作数的类别填入uop传给rename进行后续操作。对于立即数，本模块不做处理，而是将原本指令截断opcode后，传送到后级，由后级自行解构出。

在模块中，引入了对nop指令的识别，即“操作类型是ALU，且源寄存器rs1、rs2、目标寄存器rd均为x0的指令”。一旦识别出指令为nop指令，就将其屏蔽，这条指令将不会被发射到Rename阶段，减少计算资源的消耗。 

译码单元不会造成流水线堵塞，因此只向前传递后级的阻塞信号。

= 后端介绍

== 重命名 (RU)
#figure(image-crop(bp_full,
  crop-width: 550,
  crop-height: 550,
  crop-start-x: 125,
  crop-start-y: 550,
  width: 70%
), caption: "重命名架构") <ru>
从译码单元接受CORE_WIDTH个指令，执行时必须确保CORE_WIDTH个指令能够同时被重命名（不包括无效指令），否则阻塞流水线。

对指令源寄存器重命名时分别从RMT和AMT获得对应的映射关系。其中RMT优先级高于AMT，亦即当RMT中的映射关系被记为有效时，应当选取RMT，否则AMT取胜。RMT中的有效位只有在回滚时被清零，目的是为了减少冗余的复制操作。

为解决同时对指令组中一种寄存器命名的问题，本模块使用了映射关系矩阵(XLEN \* CORE_WIDTH)。对于有写入寄存器的指令，将会在相应的以该架构寄存器地址为索引的位置上将其在指令组中对应的位上置1。其后的指令若对该指令有依赖，则将从该矩阵相应行中利用有优先编码器寻找最后写入的物理寄存器地址，从而保持了正确的映射关系。

对于目的寄存器，需从PRF Freelist获取CORE_WIDTH个空闲物理寄存器，获取成功后将index写入RMT，否则stall。

== PRF Busy
在重命名单元检测到有指令写入寄存器，会把对应物理寄存器地址写入PRF_BUSY表进行登记。该表的用途是记录当前是否仍有指令需要写入指定物理寄存器，主要目的是为了判断指令进入发射队列时，源寄存器就绪的初始状态。如果指令已完成对该寄存器的写入，则后续无需监听该源寄存器的状态。

== 派遣 (DP)
从重命名单元接受CORE_WIDTH个指令，执行时必须确保CORE_WIDTH个指令能够同时被派遣，否则阻塞流水线。

此单元将根据uop的类型，将指令分别派遣到exu_issue, ld_issue, st_issue等指令发射队列。

当检测到CSR指令时，本模块将进入顺序发射模式，并暂停流水线。进入顺序模式之前，将所有乱序指令发送到发射队列中，并将该CSR指令及以后的指令屏蔽掉，再把指令组送入ROB。进入顺序模式之后，等待ROB清空了才将眼前的CSR指令逐一发送到EXU（可连续发送），此时不把指令写入ROB。若CSR指令执行完毕后仍有为派遣的乱序指令，则返回乱序模式。较原先乱序模式不同的地方在于此时的掩码屏蔽掉了之前的指令，直到掩码为零时，才释放流水线。

== 指令发射 (IQ)
#[
#show regex("[A-z]+_issue") : it => [*#lower(it)*]
#figure(image("./dispatch_issue_birds_eye_view.png"), caption: "Issue Queue 工作原理图") <iq>
#figure(image(width: 50%, "./iq.jpeg"), caption: "Issue Queue 条目") <iq_entry>
Issue queue用于向各个EXU发射命令，需要监听后续EXU是否空闲以及各操作数的就绪状态。操作数设置为就绪有三种途径：

#set enum(indent: 2em)
+ 监听PRF 中各物理寄存器的Valid信号
+ 监听EXU处的ready信号
+ 监听EXU后的级间寄存器的ready信号

在本CPU中，IQ分为三部分，分别是exu_issue, ld_issue, st_issue，其中后面二者间有数据传输，可见后续说明。

exu_issue的运行过程如下，在非满时接收dispatch模块的uop存入payloadRAM中，将有用信息（instrType、psrc）存入queue中用于判断就绪状态，等待发射，每周期根据条目就绪状态选择已就绪的指令发射给FU（内含两个ALU、一个BU、一个乘法器、一个除法器），同时向dispatch模块发送本周期发出的指令的IQ地址，用以更新IQ Free List（位于dispatch单元）。

St_issue基本与exu_issue相同，向store流水线（位于LSU中）发送uop，不同的是要向ld_issue发送每周期仍未发射的条目信息。Ld_issue需要从st_issue接收信息的目的在于解决load-store违例。

在本CPU中，store指令走完store流水线的周期固定为2，而load指令在load流水线第二级才会从stq获取前馈数据(见@lsu)，所以只需要保证在程序顺序上位于该load指令之前的store指令全部进入STU后再发射load，就可以避免load指令越过有数据依赖的store指令，错误地读取数据（store – load violation）。为此，需要一个存储依赖关系的矩阵，load指令在进入ld_issue时，就要接收当前周期st_issue中尚未发射的store条目，以二进制数(位宽为ST_ISSUE_QUEUE_DEPTH)的形式存入矩阵的一行中。每当st_issue发射一条store指令，就要更新ld_issue矩阵中对应的列，将其全部置为0表示就绪。当一条load命令所在行全为0时，表示与其相关的（比该load指令更年轻的）store指令已经全部发射，该load指令解除限制（又若load指令的psrc为ready状态，则该load指令具备发射条件）。
]

== 重排序 (ROB)
Reorder Buffer(ROB)，是一个FIFO结构，用于按照程序序存储指令，可以实现顺序retire，从而实现分支预测失误后的回滚与精确异常（本项目无需实现精确异常）

在实际工作时，ROB从dispatch接收uop并储存，把自身头尾指针以及empty/full标志传递给dispatch单元。从各个EXU接收指令完成信号，更新complete状态。ROB总是广播位于头部的两条指令，用额外的valid位（两条指令各有一个）来指示本周期是否retire该指令，用于AMT的更新、通知STQ可以写入内存、通知其他模块发生分支预测错误。

== 功能单元 (FU) <FU>
FU为ALU, BU, MUL, DIV, CSR的抽象类，主要提供基本的输出入输出端口。指定单元（BU, CSR等）有额外读写端口专门与ROB交互。

=== ALU 
ALUFU核心计算模块ALU支持12种RISC-V标准算术逻辑运算。计算单元采用全组合逻辑设计，关键路径优化为：
操作数选择器：基于OprSel枚举的多路选择器，支持4种操作数来源
32位加法器：支持SUB操作的补码转换
译码阶段：根据fu_signals.opr1_sel区分LUI/AUIPC特殊指令，对I-type指令提取instr[7:5]生成func3信号（因为接收到的instr是截去后7位opcode后的25位instr）
=== BU 
BranchFU处理控制流指令，包括条件分支和无条件跳转，通过instr_type来区分
采用双校验点设计：方向预测校验和目标地址校验来检验是否预测成功，预测失败时设置mispred信号作为错误标记并冻结后续指令提交，并产生flush信号
内部实现减法器，检测分支跳转预测结果是否正确。通过额外的端口将跳转信息写入branch_mask模块。

==== Branch mask <branchmask>
由于BU发送指令跳转指令是乱序的，因此需要判断跳转指令的优先次序。该模块通过比较BU写回指令的branch_mask来进行年龄先后判断。

Branch mask单元内含转跳指令信息的缓存，用于保存BU写回指令的信息（实际转跳地址，branch mask, 错误预测标志等等）。BU写回有效时（不论转跳与否）将触发该缓存的更新逻辑，以及到相应模块的信息广播。更新该缓存只有在写回指令比缓存中的指令更老时才有效。

每当BU写回指令表示分支判断错误时，branch_mask就会向所有的模块广播冲刷信号（通过ROB），对于前端将进行完整冲刷，而后端（执行端和发射队列）则提供对应branch mask的冲刷位，如若执行中的指令branch mask有效位包含该冲刷位，则该指令被作废。

=== MULFU
MUL部分采用基4（Radix-4）Booth编码算法进行多周期乘法，并支持4种乘法运算。由于32位的Booth乘法器乘数与被乘数均为有符号数，因此为了同时适配不同的乘法运算，本处理器中均对两者进行位数扩展。比如，MUL指令将被乘数进行有符号扩展成64位，而乘数则有符号扩展成33位（不包括运算中向左位移1位），而MULHSU对乘数进行无符号扩展。

=== DIVFU
DIV_REM部分采用非恢复式移位减法除法算法，支持2种除法和2种取余运算，并遵守RISCV spec中对除数分别为0和-1的情况。

=== CSR <csr_mitigation>
由于写入CSR寄存器时可能更改处理器的状态，所以我们按照传统的做法：ID在检测到CSRRW指令后，进入顺序发射模式，直待ROB清空后，CSR才开始接受CSR指令。

CSR寄存器通过Memory mapping把地址映射到特定的寄存器（可以是外设寄存器，或是io端口）。本处理器只实现简单的mcycle寄存器，实现方法是额外设立一个计数器，在csr读写时，使能读写端口。

== LSU
#figure(image("./lsu.jpg"), caption: "LSU 工作原理图") <lsu>

在本次处理器设计中，Load-Store Unit（LSU）由4个子模块，Store Queue（STQ）、LoadPipeline、StorePipeline以及一个面向memory的请求仲裁器（Arbiter）构成。通过子模块的相互协作，LSU完成数据写入的暂存、加载过程中的数据选择与拼接、以及最终的 memory 请求发起，构建出一个支持乱序指令执行的访存后端。

#figure(image(width: 70%, "./stq.jpg"), caption: "STQ 工作原理图") <stq>

STQ是LSU的核心缓冲结构，由三个指针(head,write_valid,tail)维护的环形结构，以STQEntry的形式保存尚未提交的store指令。每个entry记录目标地址、写入数据，有效字节掩码以及指令类型（func3），支持SB，SH，SW存储操作的按字节精度控制。Store指令向mem的提交通过head和write_valid指针进行控制，write_valid的移动依赖于ROB的commit信号。只要当head和write_valid不重合时，STQ会持续向仲裁器发起访存申请，当仲裁器接收后，head指向的store请求会存入mem，head后移一位

#figure(image("./stq_search.jpg"), caption: "STQ 索引操作") <stq_search>

STQ不仅作为写入缓冲，还承担着Load执行过程中的数据前向源这一角色。系统会在STQ中根据LoadPipeline传入的范围（head-ld_tail）、目标地址和Load指令的func3，将一个 load（最多4byte）拆成每byte单独处理，对于每一个byte，计算其实际地址，在相应的范围内查找是否存在地址匹配且尚未提交的Store条目覆盖了该byte。搜索完所有的byte后，记录每个byte是否成功找到(bytevalid)，并完成按byte拼接。在拼接过程中优先使用传入ld_tail指针最近的匹配entry中匹配该byte的数据。最终将bitvalid（由bytevalid生成）和拼接的最终数据传回给LoadPipeline。

LoadPipeline是一个四级流水线，依次完成地址计算、STQForwarding和memory请求发起、数据合并以及最后的写回操作。在完成地址计算后，Load流水线向STQ传入Forwarding需要的范围（ld_tail）、目标地址和Load指令的func3，进行Forwarding查询。此处为了防止LSV的发生，LSU和Dispatch之间有STQ尾指针状态的传递，每条load被dispatch时会记录当前STQ的tail的状态stq_tail，stq_tail会跟随这条load指令的uop一起动，最后作为我们Forwarding查找的范围依据，在load执行的时候，STQ 中更年轻的 store 被屏蔽，不能 forward 给这个 load。如果查询全部命中，访问memory 请求将被屏蔽；若仅部分命中，则会同时发起访问memory 请求。在Stage3中通过掩码（bitvalid）按位合并STQ和memory，将合并的数据根据load指令的func3进行符号扩展，适配LB/LBU等指令格式。在Stage4完成最终的写回操作

StorePipeline则是两级流水线，依次进行地址计算、最终写回STQ和ROB。功能简单，只完成地址的初步计算和指令信息的传递。

由于 LSU 内部所有访存操作共享单一 memory 接口，为避免访问冲突，系统采用轻量级的仲裁器对 LoadPipeline 和 STQ 中待提交 Store 的请求进行调度。在本设计中，memory 接口为理想 ready 信号模型，仲裁策略为轮询式公平仲裁，每周期仅允许一个请求通过，保障访存路径在高并发场景下的有序执行。当LoadPipeline发起申请但是轮询优先级没有轮到时，会stall一个周期

== 物理寄存器堆 (PRF)

物理寄存器堆存有PRF_DEPTH个寄存器，寄存器宽度为XLEN。由于存在多个FU，PRF必须有多个读写端口。

= 集成仿真

本处理器的仿真使用了verilator，并通过surfer软件观察波形。

== 仿真模块
#figure(image("./bench_cpp.png"), caption: "仿真模块") <iq>
为了减少对Chisel模拟器的依赖，本项目中调用了verilator的api，将程序载入处理器的内存中。

== 集成测试

// == 性能分析

= 小组分工说明

#table(
  columns: (1fr, 1fr),
  gutter: 3pt,
  [组员], [分工部分],
  [刘恒雨(组长)], [顶层模块+测试+文档排布+优化代码(dispatch)+branch_mask+csr],
  [杨钧铎], [ROB + Rename],
  [饶忠禹], [LSU],
  [胡英瀚], [Issue Queue],
  [李可名/赵力], [BP],
  [邢益成], [Fetch + Decode],
  [马嘉一], [FU(ALU, BP, DIV, MUL)],
  [胡继仁], [prf],
  [蔡家麒], [RAS],
)

// #bibliography()
#bibliography("bib.yml")
