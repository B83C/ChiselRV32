#import "imports.typ": *

#let s = state("mapping", (:))
#let bs = (w: 4, h: 2)
#let bl = (w: 8, h: 4)
#let bl = bs
#let spacing = 1
#let name_to_id = lower
#let id = (name) => (id: name_to_id(name), name: name)
#let entry = (args) =>  arguments(x: 0, y: 0, ..args.named())
#let left_test = (to, name, dy, space: spacing, ..args) => {
  let x = 0
  let y = 0
  let _  = context {
    let mod = s.get(to)
    let w = args.named().w
    let h = args.named().h
    if mod != none {
      x = (mod.x - w - space)
      y = (mod.y + dy)
      let _ = s.update(x => x + (lower(name):(x: x, y: y, w: w, h: h)))
    }
  }
  element.block(x: x, y: y, ..id(name), ..args)
}
#let left = (name, y, args, space: spacing) => arguments(x: (rel: - space - args.named().w, to: name_to_id(name) + ".west"), y: y, ..args.named())
#let right = (name, y, args, space: spacing) => arguments(x: (rel: space, to: name_to_id(name) + ".east"), y: y, ..args.named())
#let up = (name, y, args, ..varg) => {
  // let m = s.get()
  // let m = m.at(name_to_id(name))
  arguments(x: (rel: 0, to: name_to_id(name) + ".west"), y: y, ..args.named()) 
}
#let down = (name, y, args, ..varg) => {
  // let m = s.get().at(name)
  arguments(x: (rel: 0, to: name_to_id(name) + ".west"), y: -y, ..args.named()) 
}

#let style1 = (fill: util.colors.pink)
#let block = (misc) => {
  let id = misc.at("id")
  let y = misc.at("y")
  let h = misc.at("h")
  let _ = s.update(s => s.push(id, (y: y, h: h)))
  element.block(..misc)
}

#let c(block1, block2, name, display: true, ..args) = {
  wire.wire(
    "w-" + name,
    // style: "dodge",
    (lower(block1) + "-port-" + name, lower(block2) + "-port-" + name),
    name: if display {name} else { none }, 
    // name-pos: "middle",
    // name: name,
    directed: true,
    ..args
  )
}
#let cr(..args) = c(args.at(1), args.at(0), args.at(2), reverse: true, ..args.named())
#let cz(..args) = c(..args, style: "zigzag")
#let cd(..args) = c(..args, style: "dodge")
#let crd(..args) = cr(..args, style: "dodge")
#let crz(..args) = cr(..args, style: "zigzag")

#set text(size: 5pt)
#context[
   #circuit(length: 2em,{
    element.group(name: "Toplevel", {
      element.group(..id("Frontend"), {
        block(entry(arguments( ..bl, ..style1, ..id("Fetch"),
          ports: (
            east: (
              (id: "FetchPacket"),
            ),
            south: (
              (id: "PC_BP"),
              (id: "PC_NEXT"),
            ),
          )
        )))
        left_test("Fetch", "huh", 0, ..bl, ..style1)
        block(down("Fetch", 5, arguments( ..bl, ..style1, ..id("Branch predictor"),
          ports: (
            north: (
              (id: "PC_BP"),
              (id: "PC_NEXT"),
            ),
          )
        )))
        block(right("Fetch", 0, arguments( ..bl, ..style1, ..id("Decode"),
          ports: (
            west: (
              (id: "FetchPacket"),
            ),
            east: (
              (id: "Decoded"),
            ),
          )
        )))
      })
      element.group(..id("Scheduler"),
      {
        block(right(space: 3, "Decode", 0, arguments( ..bl, ..style1, ..id("Rename"),
          ports: (
            west: (
              (id: "Decoded"),
            ),
            east: (
              (id: "renamed"),
            ),
            north: (
              (id: "RMTReq"),
            ),
            south: (
              (id: "PRFLookup"),
              (id: "PRFResp"),
            )
          )
        )))
        element.group(name-anchor: "north", ..id("PRF Mapping"), {
          block(up("Rename", 5, arguments( ..bs, ..style1, ..id("AMT"),
            ports: (
              south: (
                (id: "RMTReq"),
              )
            )
          )))
          block(up("AMT", 7 , arguments( ..bs, ..style1, ..id("RMT"),
            ports: (
            )
          )))
        }
        )
        block(down("Rename", 5, arguments( ..bl, ..style1, ..id("PRF Freelist"),
          ports: (
            north: (
              (id: "PRFLookup"),
              (id: "PRFResp"),
            ),
            east: (
              (id: "renamed"),
            )
          )
        )))
        element.group(..id("Dispatch Unit"),
        {
          // element.group(..id("IQ Row Generator"),
          // {
            block(right(space: 2, "Rename", 0, arguments( ..bs, ..style1, ..id("Dispatcher"),
              ports: (
                west: (
                  (id: "renamed"),
                ),
                north: (
                  (id: "freeiq"),
                ),
                east: (
                  (id: "dispatched"),
                )
              )
            )))
            // block(down("Dispatcher", 3, arguments( ..bs, ..style1, ..id("Ready Bit Table"),
            //   ports: (
            //     north: (
            //       id(""),
            //     ),
            //   )
            // )))
            block(up("Dispatcher", 6, arguments( ..bs, ..style1, ..id("IQ Free List"),
              ports: (
                south: (
                  (id: "freeiq"),
                )
              )
            )))
          })
        })
        element.group(..id("Issue Queue"),
        {
          block(right(space: 2, "Dispatcher", 9, arguments( ..bs, ..style1, ..id("Payload\nRam"),
            ports: (
              west: (
                (id: "dispatched"),
              ),
            )
          )))
          block(down("Payload\nRam", -6, arguments( ..bs, ..style1, ..id("Exu Issue"),
            ports: (
              west: (
                (id: "dispatched"),
              ),
              east: (
                (id: "issued"),
              )
            )
          )))
          block(down("Exu Issue", -3, arguments( ..bs, ..style1, ..id("LD Issue"),
            ports: (
              west: (
                (id: "dispatched"),
              ),
              east: (
                (id: "issued"),
              )
            )
          )))
          block(down("LD Issue", -0, arguments( ..bs, ..style1, ..id("ST Issue"),
            ports: (
              west: (
                (id: "dispatched"),
              ),
              east: (
                (id: "issued"),
              )
            )
          )))
          block(down("ST Issue", 4, arguments( ..bs, ..style1, ..id("ROB"),
            ports: (
              west: (
                (id: "dispatched"),
              ),
              east: (
                (id: "prf_wb"),
              )
            )
          )))
        })
      })
      // element.multiplexer(..right("Exu Issue", -1, arguments(w: 1, h: 2, 
      //   id: "multiplexer",
      //   entries: 2
      // )))
      element.group(..id("Execution"),
      {
        block(right(space: 2, "ST Issue", 4, arguments( ..bs, ..style1, ..id("PRF/\nBypass Read"),
          ports: (
            north: (
              (id: "bypassed"),
            ),
            west: (
              (id: "issued"),
            ),
            east: (
              (id: "issued_bypassed"),
            ),
            south: (
              (id: "prf_read"),
            ),
          )
        )))
        block(right(space: 2, "PRF/\nBypass Read", 6, arguments( ..bs, ..style1, ..id("Exec Unit"),
          ports: (
            west: (
              (id: "issued_bypassed"),
            ),
            east: (
              (id: "executed"),
            )
          )
        )))
        block(right(space: 2, "Exec Unit", 4, arguments( ..bs, ..style1, ..id("PRF/\nBypass Write"),
          ports: (
            north: (
              (id: "bypassed"),
            ),
            west: (
              (id: "executed"),
            ),
            south: (
              (id: "prf_wb"),
            )
          )
        )))
        block(down(space: 1, "Exec Unit", -2, arguments( ..bs, ..style1, ..id("LSU"),
          ports: (
            west: (
              (id: "issued_bypassed"),
            ),
            east: (
              (id: "executed"),
            )
          )
        )))
        block(down(space: 1, "PRF/\nBypass Read", 1, arguments( ..bs, ..style1, ..id("PRF"),
          ports: (
            north: (
              (id: "prf_read"),
            ),
            east: (
              (id: "prf_wb"),
            )
          )
        )))
      })
      c("fetch", "branch predictor", "PC_NEXT")
      c("fetch", "decode", "FetchPacket", display: false)
      cr("fetch", "branch predictor", "PC_BP")
      c("decode", "rename", "Decoded")
      c("rename", "amt", "RMTReq")
      c("rename", "prf freelist", "PRFLookup")
      cr("rename", "prf freelist", "PRFResp")
      c("rename", "dispatcher", "renamed", display: false)
      cr("dispatcher", "iq free list", "freeiq", display: false)
      cz("dispatcher", "exu issue", "dispatched", display: false)
      cz("dispatcher", "ld issue", "dispatched", display: false)
      cz("dispatcher", "st issue", "dispatched", display: false)
      cz("dispatcher", "payload\nram", "dispatched", display: false)
      cz("dispatcher", "rob", "dispatched", display: false)
      cz("exu issue", "PRF/\nBypass Read", "issued", display: false)
      cz("ld issue", "PRF/\nBypass Read", "issued", display: false)
      cz("st issue", "PRF/\nBypass Read", "issued", display: false)
      crz("exec unit", "PRF/\nBypass Read", "issued_bypassed", display: false)
      crz("lsu", "PRF/\nBypass Read", "issued_bypassed", display: false)
      cz("exec unit", "PRF/\nBypass Write", "executed", display: false)
      cz("lsu", "PRF/\nBypass Write", "executed", display: false)
      crd("PRF/\nBypass Read", "PRF/\nBypass Write", "bypassed", dodge-y: 10, dodge-margins: (0.5, 0.5), display: false)
      cr("prf", "PRF/\nBypass Read", "prf_read", display: false)
      crd("prf", "PRF/\nBypass Write", "prf_wb", dodge-sides: ("south", "east"),display: false)
      crd("rob", "PRF/\nBypass Write", "prf_wb", dodge-y: -5, display: false)
      // c("fetch", "decode", "FetchPacket", display: false)
      // c("branch predictor", "fetch", "PC_NEXT")
    })

  }) 
]
