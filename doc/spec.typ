#import "imports.typ": *
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


#let iob = regex("IO\(new\s+Bundle\s+\{((?:[^{}]|\{[^{}]*\})*)\}\)") 

#let r(path) = "/src/main/rsd_rv32/" + path //Path relative to porject root
#let match(content, object_type, name_regex, inheritance) = {
  content.matches(
    // regex("((?:/{2,}.*?\n)*)class\s+(" + name_prefix + "\w+" + name_suffix + ")\s*(\((?:[^()]|[^()]\)\([^()])*\))\s+extends\s+" + class + "[\s\n\r]*\{[\s\n\r]*((?:[^{}]|\{[^{}]*\})*)\}")
    regex("((?:/{2,}.*?\n)*)" + object_type + "\s+(" +  name_regex + ")\s*(\((?:[^()]|\)\()*\))\s+" +
    if inheritance != none { "(extends)\s+" + inheritance } else {""}
    + "[^{]*\{[^\n]*\n?")
  )
}
#let rend(module-path, object_type, name_regex, inheritance, params: true, ios: true, snippet: true) = {
  let content = read(module-path)
  
  let sections = []

  let matches = match(content, object_type, name_regex, inheritance)  

  for unit in matches {
    let comment = unit.captures.at(0).replace(regex("/{2,}\s*"), "")
    let module = unit.captures.at(1)
    let params_c = unit.captures.at(2)
    let code_end_pos = unit.end
    let brac_count = 1

    while brac_count > 0 {
      let slice = content.slice(code_end_pos)
      let left_brac = slice.position("{")
      let right_brac = slice.position("}")
      if left_brac == none or left_brac > right_brac {
        brac_count -= 1
        code_end_pos += right_brac + 1
      } else if right_brac == none or right_brac > left_brac{
        brac_count += 1
        code_end_pos += left_brac + 1
      } else {
        break
      }
    }

    let code = content.slice(unit.end, code_end_pos - 1)
    
    // let code = unit.captures.at(3)
    let io_bundle = code.match(regex("IO\(new\s+Bundle\s+\{((?:[^{}]|\{[^{}]*\})*)\}\)"))
    let ios = none
    let params_c = params_c.matches(
      regex("val\s+(\w+)\s*:\s*([^/,\n\r]*),?[\s\r]*(?:/\*|/{2,})?([^,*\n\r)]*)")
      // regex("\(\s*val\s+((?:[^()]|\([^()]\))*)\)")
    )
    if io_bundle != none {
      
      io_bundle = io_bundle
        .captures.first()
      let ios = io_bundle.matches(
        regex("val\s+(\w+)\s*=\s*([^/\n\r]*)(?:/\*|/{2,})?([^\n\r]*)")
      )
    }
    sections += [
      == #module

      #comment

      #if params_c != none and params and params_c.len() > 0 {
        [#module 的构造参数如下：]
        table(
          columns: 3,
          gutter: 3pt,
          [*Name*], [*Type*], [*Description*],
          ..params_c.map(x => {
            let cap = x.captures
            (cap.at(0), raw(cap.at(1), lang: "scala"), cap.at(2, default: "").replace("\*\/", "").trim())
          }).flatten()
        )  
      }


      #if ios != none and ios and ios.len() > 0 {
        [IO端口定义如下：]

        table(
          columns: 3,
          gutter: 3pt,
          [*Name*], [*Type*], [*Description*],
          ..ios.map(x => {
            let cap = x.captures.slice(0, 3)
            ([*#cap.at(0)*], raw(cap.at(1), lang: "scala"), cap.at(2).replace("*/", "").trim())
          }).flatten()
        )
      }

      #if snippet {
        raw(code, lang: "scala", block: true)
      }
    ]

  }
  sections
}

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
/ IRL: Instruction Replay Logic
/ STQ: STore Queue
/ LDQ: LoaD Queue

= Overview
#include "diagram.typ"

= Parameters
#rend(r("common/common.scala"), "(?:case\s+)*class", "Parameters", none)

= Interface
#rend(r("common/common.scala"), "(?:case\s+)*class", "Parameters", none)

= Microarchitecture
本处理器为乱序执行多发射RV32IM架构，其设计主要借鉴于RSD以及BOOM。 

// (Filepath, object type to match (like class, abstract class or object or case class), object name to match, inheritance pattern)
// \w+ is a Regex pattern, meanning it will match a contiguous range of characters of at least 1 character long
// \s is space, (pred)* means matching at least 0 amount of pred
// (?:) is a non capturing group, meaning it will be omitted in the match result (i.e, it will be matched, but the exact value won't be returned) 
// the notion 'group' is necessary since the pattern ab* will only match a, ab, abb, abbb, abbb..., but not ab,abab,abab. So to make the latter happen you have to group 'ab' into (ab), but writing this way will cause it to appear in the final result. So if you wish to match ab's, but don't want it to contaminate the search result, use (?:ab)
// For detailed syntax, please refer to Regex.
#rend(r("common/common.scala"), "(?:abstract)*\s?class", "\w+", "Module")
#rend(r("common/freelist.scala"), "(?:abstract)*\s?class", "\w+", "[^{]*")
#rend(r("execution/lsu.scala"), "(?:abstract)*\s?class", "\w+", "[^{]*")
