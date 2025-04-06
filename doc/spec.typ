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

#let match(content, name_regex, class) = {
  content.matches(
    // regex("((?:/{2,}.*?\n)*)class\s+(" + name_prefix + "\w+" + name_suffix + ")\s*(\((?:[^()]|[^()]\)\([^()])*\))\s+extends\s+" + class + "[\s\n\r]*\{[\s\n\r]*((?:[^{}]|\{[^{}]*\})*)\}")
    regex("((?:/{2,}.*?\n)*)class\s+(" +  name_regex + ")\s*(\((?:[^()]|\)\()*\))\s+extends\s+" + class + "[^{]*\{[^\n]*\n?")
  )
}
#let render(module-path, object-name-pattern, object-type, show-params, show-ios, show-snippet) = {
  let content = read(module-path)
  
  let sections = []

  let matches = match(content, object-name-pattern, object-type)  

  for unit in matches {
    let comment = unit.captures.at(0).replace(regex("/{2,}\s*"), "")
    let module = unit.captures.at(1)
    let params = unit.captures.at(2)
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
      .captures.first()
    let params = params.matches(
      regex("val\s+(\w+)\s*:\s*([^,/\n\r]*)(?:/\*|/{2,})?([^,\n\r\)]*)")
      // regex("\(\s*val\s+((?:[^()]|\([^()]\))*)\)")
    )
    let ios = io_bundle.matches(
      regex("val\s+(\w+)\s*=\s*([^/\n\r]*)(?:/\*|/{2,})?([^\n\r]*)")
    )

    sections += [
      == #module

      #comment

      #if show-params and params.len() > 0 {
        [#module 的构造参数如下：]
        table(
          columns: 3,
          gutter: 3pt,
          [*Name*], [*Type*], [*Description*],
          ..params.map(x => {
            let cap = x.captures.slice(0, 3)
            (cap.at(0), raw(cap.at(1), lang: "scala"), cap.at(2).replace("\*\/", "").trim())
          }).flatten()
        )  
      }


      #if show-ios and ios.len() > 0 {
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

      #if show-snippet {
        raw(code, lang: "scala", block: true)
      }
    ]
  }
  sections
}

= Revision History


#set heading(numbering: "1.1")
#outline()

= Terminology
/ LE: None

= Overview
#include "diagram.typ"

= Parameters
= Interface
= Microarchitecture
#for (path, pattern) in (
  ("common/common.scala", "\w+"),
  ("common/freelist.scala", "\w+"),
) {
  render("/src/main/rsd_rv32/" + path, pattern, "Module", true, true, true)
}
