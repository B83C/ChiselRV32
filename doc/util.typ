#import "imports.typ": *
#set heading(numbering: "1.1")
#set table(
  stroke: (x, y) => if y == 0 {
    (bottom: 0.7pt + black)
  },
  align: (x, y) => (
    if x > 0 { center }
    else { left }
  )
)

#let r(path) = "/src/main/scala/rsd_rv32/" + path //Path relative to porject root

#let find_block(slice, map: ("{": 0, "}": 0, "(": 1, ")": 1), inclusive: false) = {
  let brac_count = map.pairs().chunks(2).map(_ => 0)
  let start_pos = slice.len() - 1
  let inc_index = 0
  for ((kl, v), (_, _)) in map.pairs().chunks(2) {
    let left_brac = slice.position(kl)
    if left_brac != none and start_pos > left_brac {
      start_pos  = left_brac + kl.len()
      brac_count.at(inc_index) = 0
      brac_count.at(v) = 1
      inc_index = v
    }
  }
  let next_new_line = slice.position("\n")  
  if next_new_line != none and next_new_line < start_pos {
    return (none, 0, next_new_line - 1)
  }
  let slice = slice.slice(start_pos)
  let code_end_pos = 0
  let last_token_len = 1
  while brac_count.any(x => x > 0) {
    let cslice = slice.slice(code_end_pos)
    let closest = map.pairs().chunks(2).map(a => {
      let ((kl, v), (kr, _)) = a
      let left_brac = cslice.position(kl)
      let right_brac = cslice.position(kr)
      if left_brac != none and right_brac != none {
        if left_brac > right_brac {
          (right_brac, v, -1, kr.len())  
        } else {
          (left_brac, v, +1, kl.len())
        }
      } else if left_brac != none {
          (left_brac, v, +1, kl.len())  
      } else if right_brac != none {
          (right_brac, v, -1, kr.len())  
      } else {
         none
      }
    }).filter(it => it != none)
    if closest.len() > 0 {
      let (b, v, i, l) = closest.sorted(key: it => it.first()).first()
      brac_count.at(v) += i
      last_token_len = l
      code_end_pos += b + l
    } else if brac_count.any(x => x > 0)  {
        code_end_pos = slice.len() - 1
        break;
    }
  }
  (slice.slice(0, code_end_pos - last_token_len), start_pos + 1, start_pos + 1 + code_end_pos - last_token_len)
}
#let find_comment_block(args) = find_block(args, map:("/*": 0, "*/": 0))
#let get_comment(str) = {
  let line_comment = str.match(regex("//(.*)"))
  let nl_start = str.position("\n") // TODO: inefficient
  if line_comment != none {
    if nl_start != none and nl_start < line_comment.start {
      (none, str.len()-1)
    } else {
      (line_comment.captures.first().trim(), line_comment.start)
    }
  } else {
    let (block_comment, _, next_new_line) = find_comment_block(str)
    if block_comment != none {
      (block_comment.trim(), next_new_line)
    } else {
      (none, str.len()-1)
    }
  }
}

// #let tet = "    val io = IO(new ROBIO({asoentuh})

// "
// #let (match, start, end) = find_block(tet)
// #let dbg = tet.slice(start, end)
// t
// #let tet = "iasontehus /* aosentuh
//   new line
//  */"
// #let tet = "\niasontehus // oesunth
// #let tet = "\niasontehus // oesunth
// next line
// "
// #let ttett = get_comment(tet)
// #let (match, start, end) = find_comment_block(tet)
// #let dbg = tet.slice(start, end)
// t
#let add_modules(module-path) = {
  let content = read(module-path)
  
  let sections = []

  let matches = content.matches(regex("((?:/{2,}.*?\n)*)?(class|trait|abstract class|case class|object)\s+(\w+)\s*([^{]*)"))

  matches.map(unit => {
    let comment = unit.captures.at(0).replace(regex("/{2,}\s*"), "").trim()
    let type = unit.captures.at(1)
    let module = unit.captures.at(2)
    let params_inheritance = unit.captures.at(3)
    let params_c = params_inheritance.match(regex("(\((?:[^()]|\)\()*\))"))
    let inheritance  = params_inheritance.match(regex("extends([\w\s]+)"))
    if inheritance != none {
      inheritance =  inheritance.captures.first().split("with").map(x => x.trim())
    }
    let code_end_pos = unit.end
    let (code, start, end) = find_block(content.slice(code_end_pos))

    let io_ports = none
    if params_c != none {
      let params = params_c.captures.first()
      params_c = params.matches(
        regex("(?:^implicit\s+)?(?:val\s+)?(\w+)\s*:\s*([^/,\n\r)]*)")
      ).filter(p => not p.text.contains("implicit")).map(p => { // TODO
        let comment = get_comment(params.slice(p.end))
        (p.captures.first(): (p.captures.at(1), comment.first()))
      }).reduce((acc, p) => acc + p)
    }
    let find_members(block) = {
      let params = block.matches(
        regex("val\s+(\w+)\s*=\s*"))
      params.map(p => {
        let val_start = block.slice(p.end)
        if not val_start.starts-with("IO") {
          let (block, _, block_end) = find_block(val_start)
          let (desc, desc_start) = get_comment(val_start)
          (p.captures.first(): (
            val_start.slice(0, if block == none {desc_start} else {block_end}).trim().replace(regex("//(.*)"), "").trim()
          , desc))
        } else {
          none
        }
      }).reduce((acc, p) => acc + p)
    }
    let io_bundle = code.matches(regex("val\s+(\w+)\s*=\s*IO\(")).map(io => {
      let block = find_block(code.slice(io.end - 1)).first()
      if block.contains("new Bundle") {
        find_members(block)
      } else {
        let (comment, comment_start) = get_comment(block)
        ((io.captures.first(): (block, comment)))
      }
    }).reduce((acc, p) => acc + p)
    let members = find_members(code)
    (module.trim(): (
      desc: comment,
      name: module.trim(),
      inheritance: inheritance,
      type: type,
      params: params_c,
      io: io_bundle,
      members: members,
    ))
  }).reduce((acc, p) =>  acc + p)
}


#let ss(s) = s + ".scala"
#let e(s) = r("execution/" + ss(s))
#let s(s) = r("scheduler/" + ss(s))
#let f(s) = r("frontend/" + ss(s))
#let c(s) = r("common/" + ss(s))

#let interfaces_used = state("interfaces_used", [])
#let interfaces_shown = state("interfaces_shown", (:))

// 所有class的汇总
// Dictionary类型，成员为：
//   desc: string,
//   name: string,
//   inheritance: string array, 就是继承的所有类型，比如Bundle, Module等
//   type: string, 就是类型种类，比如abstract class, class, trait等
//   params: dictonary, 存储数据结构为parameter name: (type, comment)
//   members: dictionary, 同上, 是类型的所有成员，对于Bundle来说，是有意义的
//   io: dictionary, 同上, io是members的特例，即val blabla = IO(...)归属与IO的一部分
#let c = (
  r("core.scala"),
  c("common"),
  c("freelist"),
  c("mem"),
  c("ram"),
  c("uop"),
  f("branch_predict"),
  f("decode"),
  f("fetch"),
  s("dispatch"),
  s("exu_issue"),
  s("ld_issue"),
  s("rename"),
  s("st_issue"),
  e("lsu"),
  e("prf"),
  e("execution"),
).fold((:), (acc, p) => acc + add_modules(p))

#let names = c.keys()

#let cmap = (
  "abstract class" : orange,
  "class" : orange,
  "case class" : orange,
  "trait" : yellow,
  "object" : yellow,
)

#let cmap = (
  "bundle" : orange,
  "module" : yellow,
)

#let cm(i) = {
  cmap.at(lower(i), default: yellow) 
}

#let module_desc(m) = {
  let module = m.name
  let class_tag = box(baseline: 20%, fill: luma(230), inset: 4pt, radius: 4pt, text(size: 0.6em, m.type)) 
  let inheritance_tag =  if m.inheritance != none {
    m.inheritance.map(it => [#box(baseline: 20%, fill: cm(it), inset: 4pt, radius: 4pt, text(size: 0.6em, it))]).reduce((acc, p) => acc + p)
  } else {
    none
  }
  {
    [ == #module #class_tag #inheritance_tag
      #label(module)
    ]
    m.desc 
  }
}

#let module_content(m, auto_ref: true) = {
  let module = m.name
  let p = m.params
  if p != none  {
     {
      [\ #module 的构造参数如下：]
      table(
        columns: 3,
        gutter: 3pt,
        [*Name*], [*Type*], [*Description*],
        ..p.pairs().map(v => {
          let (k, v) = v
          let (type, desc) = v
          if desc == none {
            desc = ""
          }
          (k, raw(type, lang: "scala"), desc.replace("\*\/", "").trim())
        }).flatten()
      )  
    }   
  } else {
    none
  }
}

#let io_content(m) = {
  let io = m.io
  {
    [
      #for kv in io.pairs(){
        let (_, v) = kv
        let (type, _) = v
        for i in names {
            let t = type.contains(regex("\b" +i + "\b"))
            context {
              let shown = interfaces_shown.get()
              if t and not shown.at(i, default: false) {
                interfaces_shown.update(x =>
                  x + (i: true)
                )
                interfaces_used.update(x =>
                  x + [
                    #module_desc(c.at(i))
                    #module_content(c.at(i))
                  ]
                )
              }
            }
        }
      }
    ]
    [\ IO端口定义如下：]
    table(
      columns: 3,
      gutter: 3pt,
      [*Name*], [*Type*], [*Description*],
      ..io.pairs().map(kv => {
        let (k, v) = kv
        let (type, desc) = v
        
        let type = [
          #show regex("\w+"): it => {
            if names.contains(it.text) {
              link(label(it.text), [#it.text])
            } else {
              it
            }
          }
          #raw(type, lang: "scala")
        ]
        if desc == none {
          desc = ""
        }
        (k, type, desc.replace("\*\/", "").trim())
      }).flatten()
    )
  }
}

#let bundle_content(m) = {
  let members = m.members
  assert(m.inheritance.contains("Bundle"), message: "Only Bundles are accepted")
  {
    [\ 定义如下：]
    table(
      columns: 3,
      gutter: 3pt,
      [*Name*], [*Type*], [*Description*],
      ..members.pairs().map(kv => {
        let (k, v) = kv
        let (type, desc) = v
        if desc == none {
          desc = ""
        }
        (k, raw(type, lang: "scala"), desc.replace("\*\/", "").trim())
      }).flatten()
    )
  }
}

#let param_content(m) = {
  let params = m.params
  {
    [\ 定义如下：]
    table(
      columns: 3,
      gutter: 3pt,
      [*Name*], [*Type*], [*Description*],
      ..params.pairs().map(kv => {
        let (k, v) = kv
        let (type, desc) = v
        if desc == none {
          desc = ""
        }
        (k, raw(type, lang: "scala"), desc.replace("\*\/", "").trim())
      }).flatten()
    )
  }
}

#let dp(m, ..arg) = {
  module_desc(m, ..arg)
  module_content(m, ..arg)
}

#let dpio(m, ..arg) = {
  module_desc(m, ..arg)
  module_content(m, ..arg)
  io_content(m, ..arg)
}


// #context interfaces_used.final()

// = Microarchitecture
// 本处理器为乱序执行多发射RV32IM架构，其设计主要借鉴于RSD以及BOOM。  

// #dpio(c.ExecutionUnit)
// #dpio(c.ALU)

