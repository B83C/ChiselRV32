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

= Overview
#include "diagram.typ"

= Parameters
#rend(r("common/common.scala"), "(?:case\s+)*class", "Parameters", none)

= Interface
#rend(r("common/common.scala"), "(?:abstract\s+)*class", "\w+", "Bundle")
#rend(r("frontend/branch_predict.scala"), "(?:abstract\s+)*class", "\w+", "Bundle", ios: match_all)
// #rend(r("frontend/decoder.scala"), "(?:abstract\s+)*class", "\w+", "Bundle", ios: match_all)
#rend(r("frontend/fetch.scala"), "(?:abstract\s+)*class", "\w+", "Bundle", ios: match_all)
#rend(r("scheduler/issue.scala"), "(?:abstract\s+)*class", "\w+", "Bundle", ios: match_all)
#rend(r("scheduler/rename.scala"), "(?:abstract\s+)*class", "\w+", "Bundle", ios: match_all)
#rend(r("scheduler/rob.scala"), "(?:abstract\s+)*class", "\w+", "Bundle", ios: match_all)
// #rend(r("scheduler/scheduler.scala"), "(?:abstract\s+)*class", "\w+", "Bundle", ios: match_all)
#rend(r("execution/execution.scala"), "(?:abstract\s+)*class", "\w+", "Bundle", ios: match_all)
#rend(r("execution/lsu.scala"), "(?:abstract\s+)*class", "\w+", "Bundle", ios: match_all)
#rend(r("execution/prf.scala"), "(?:abstract\s+)*class", "\w+", "Bundle", ios: match_all)

= Microarchitecture
本处理器为乱序执行多发射RV32IM架构，其设计主要借鉴于RSD以及BOOM。  

// (Filepath, object type to match (like class, abstract class or object or case class), object name to match, inheritance pattern)
// \w+ is a Regex pattern, meanning it will match a contiguous range of characters of at least 1 character long
// \s is space, (pred)* means matching at least 0 amount of pred
// (?:) is a non capturing group, meaning it will be omitted in the match result (i.e, it will be matched, but the exact value won't be returned) 
// the notion 'group' is necessary since the pattern ab* will only match a, ab, abb, abbb, abbb..., but not ab,abab,abab. So to make the latter happen you have to group 'ab' into (ab), but writing this way will cause it to appear in the final result. So if you wish to match ab's, but don't want it to contaminate the search result, use (?:ab)
// For detailed syntax, please refer to Regex.
#rend(r("core.scala"), "(?:abstract\s+)*class", "\w+", "Module")
#rend(r("frontend/branch_predict.scala"), "(?:abstract\s+)*class", "\w+", "Module")
// #rend(r("frontend/decoder.scala"), "(?:abstract\s+)*class", "\w+", "Module")
#rend(r("frontend/fetch.scala"), "(?:abstract\s+)*class", "\w+", "Module")
#rend(r("scheduler/issue.scala"), "(?:abstract\s+)*class", "\w+", "Module")
#rend(r("scheduler/rename.scala"), "(?:abstract\s+)*class", "\w+", "Module")
#rend(r("scheduler/rob.scala"), "(?:abstract\s+)*class", "\w+", "Module")
// #rend(r("scheduler/scheduler.scala"), "(?:abstract\s+)*class", "\w+", "Module")
#rend(r("execution/execution.scala"), "(?:abstract\s+)*class", "\w+", "Module")
#rend(r("execution/lsu.scala"), "(?:abstract\s+)*class", "\w+", "Module")
#rend(r("execution/prf.scala"), "(?:abstract\s+)*class", "\w+", "Module")
