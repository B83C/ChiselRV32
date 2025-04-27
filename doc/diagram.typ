#import "imports.typ": *

#let s = state("mapping", (:))
#let bs = (w: 4, h: 2)
#let bl = (w: 8, h: 4)
#let bl = bs
#let spacing = 1
#let name_to_id = lower
#let entry = (args) =>  arguments(x: 0, y: 0, ..args.named())
#let left = (name, dy, ..args) => {
  context {
    let mod = s.get(name)
    if mod != none {
    }
  }
  block(space: 3, "Decode", 0, ..args)
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

#let id = (name) => (id: name_to_id(name), name: name)
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
#let cr(..args) = c(args.at(1), args.at(0), ..args.pos().slice(2), reverse: true)

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
              (id: "Renamed"),
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
          )
        )))
        element.group(..id("Dispatch Unit"),
        {
          // element.group(..id("IQ Row Generator"),
          // {
            block(right(space: 2, "Rename", 0, arguments( ..bs, ..style1, ..id("Dispatcher"),
              ports: (
                north: (
                  id(""),
                ),
              )
            )))
            block(down("Dispatcher", 3, arguments( ..bs, ..style1, ..id("Ready Bit Table"),
              ports: (
                north: (
                  id(""),
                ),
              )
            )))
            block(up("Dispatcher", 3, arguments( ..bs, ..style1, ..id("IQ Free List"),
              ports: (
                north: (
                  id(""),
                ),
              )
            )))
          })
        })
        element.group(..id("Issue Queue"),
        {
          block(right(space: 2, "Dispatcher", 2, arguments( ..bs, ..style1, ..id("Payload\nRam"),
            ports: (
              north: (
                id(""),
              ),
            )
          )))
          block(right(space:2, "Dispatcher", 4, arguments( ..bs, ..style1, ..id("Exu Issue"),
            ports: (
              north: (
                id(""),
              ),
            )
          )))
          block(down("Exu Issue", 2, arguments( ..bs, ..style1, ..id("LD Issue"),
            ports: (
              north: (
                id(""),
              ),
            )
          )))
          block(down("LD Issue", 5, arguments( ..bs, ..style1, ..id("ST Issue"),
            ports: (
              north: (
                id(""),
              ),
            )
          )))
          // block(right(space: 0, "Wakeup Logic", -1, arguments( ..bs, ..style1, ..id("Select Logic"),
          //   ports: (
          //     north: (
          //       id(""),
          //     ),
          //   )
          // )))
        })
        block(down("ST Issue", 6, arguments( ..bs, ..style1, ..id("ROB"),
          ports: (
            north: (
              id(""),
            ),
          )
        )))
      })
      element.multiplexer(..right("Exu Issue", -1, arguments(w: 1, h: 2, 
        id: "multiplexer",
        entries: 2
      )))
      element.group(..id("Execution"),
      {
        block(right(space: 1, "multiplexer", 0, arguments( ..bs, ..style1, ..id("PRF/\nBypass Read"),
          ports: (
          )
        )))
        block(right(space: 1, "PRF/\nBypass Read", 0, arguments( ..bs, ..style1, ..id("Exec Unit"),
          ports: (
          )
        )))
        block(right(space: 1, "Exec Unit", 0, arguments( ..bs, ..style1, ..id("PRF/\nBypass Write"),
          ports: (
          )
        )))
        block(down(space: 1, "Exec Unit", 3, arguments( ..bs, ..style1, ..id("PRF"),
          ports: (
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
      // c("fetch", "decode", "FetchPacket", display: false)
      // c("branch predictor", "fetch", "PC_NEXT")
    })

  }) 
]

// #circuit({
//   element.group(id: "toplvl", name: "Toplevel", {
//     element.group(
//       id: "proc",
//       name: "Processor",
//       padding: 1.5em,
//       stroke: (dash: "dashed"),
//       {
//       element.block(
//         x: 0, y: 0, w: 8, h: 4,
//         id: "dp",
//         fill: util.colors.pink,
//         name: "Datapath",
//         ports: (
//           north: (
//             (id: "clk", clock: true, small: true),
//             (id: "Zero"),
//             (id: "Regsrc"),
//             (id: "PCSrc"),
//             (id: "ResultSrc"),
//             (id: "ALUControl"),
//             (id: "ImmSrc"),
//             (id: "RegWrite"),
//             (id: "dummy")
//           ),
//           east: (
//             (id: "PC", name: "PC"),
//             (id: "Instr", name: "Instr"),
//             (id: "ALUResult", name: "ALUResult"),
//             (id: "dummy"),
//             (id: "WriteData", name: "WriteData"),
//             (id: "ReadData", name: "ReadData"),
//           ),
//           west: (
//             (id: "rst"),
//           )
//         ),
//         ports-margins: (
//           north: (0%, 0%),
//           west: (0%, 70%)
//         )
//       )
      
//       element.block(
//         x: 0, y: 7, w: 8, h: 3,
//         id: "ctrl",
//         fill: util.colors.orange,
//         name: "Controller",
//         ports: (
//           east: (
//             (id: "Instr", name: "Instr"),
//           ),
//           south: (
//             (id: "dummy"),
//             (id: "Zero"),
//             (id: "Regsrc"),
//             (id: "PCSrc"),
//             (id: "ResultSrc"),
//             (id: "ALUControl"),
//             (id: "ImmSrc"),
//             (id: "RegWrite"),
//             (id: "MemWrite")
//           )
//         ),
//         ports-margins: (
//           south: (0%, 0%)
//         )
//       )
//       wire.wire(
//         "w-Zero",
//         ("dp-port-Zero", "ctrl-port-Zero"),
//         name: "Zero",
//         name-pos: "start",
//         directed: true
//       )
//       for p in ("Regsrc", "PCSrc", "ResultSrc", "ALUControl", "ImmSrc", "RegWrite") {
//         wire.wire(
//           "w-" + p,
//           ("ctrl-port-"+p, "dp-port-"+p),
//           name: p,
//           name-pos: "start",
//           directed: true
//         )
//       }

//       draw.content(
//         (rel: (0, 1em), to: "ctrl.north"),
//         [*RISCV single*],
//         anchor: "south"
//       )
//     })
    
//     element.block(
//       x: (rel: 4.5, to: "dp.east"),
//       y: (from: "dp-port-ReadData", to: "RD"),
//       w: 3, h: 4,
//       id: "dmem",
//       fill: util.colors.green,
//       name: "Data\n Memory",
//       ports: (
//         north: (
//           (id: "clk", clock: true, small: true),
//           (id: "WE", name: "WE")
//         ),
//         west: (
//           (id: "dummy"),
//           (id: "dummy"),
//           (id: "A", name: "A"),
//           (id: "dummy"),
//           (id: "WD", name: "WD"),
//           (id: "RD", name: "RD"),
//         )
//       ),
//       ports-margins: (
//         north: (0%, 10%)
//       )
//     )
//     wire.wire(
//       "w-DataAddr",
//       ("dp-port-ALUResult", "dmem-port-A"),
//       name: "DataAddr",
//       name-pos: "end",
//       directed: true
//     )
//     wire.wire(
//       "w-WriteData",
//       ("dp-port-WriteData", "dmem-port-WD"),
//       name: "WriteData",
//       name-pos: "end",
//       directed: true
//     )
//     wire.wire(
//       "w-ReadData",
//       ("dmem-port-RD", "dp-port-ReadData"),
//       name: "ReadData",
//       name-pos: "end",
//       reverse: true,
//       directed: true
//     )
//     wire.wire(
//       "w-MemWrite",
//       ("ctrl-port-MemWrite", "dmem-port-WE"),
//       style: "zigzag",
//       name: "MemWrite",
//       name-pos: "start",
//       zigzag-dir: "horizontal",
//       zigzag-ratio: 80%,
//       directed: true
//     )
//     wire.stub(
//       "dmem-port-clk", "north",
//       name: "clk", length: 3pt
//     )

//     element.block(
//       x: (rel: 3.5, to: "dp.east"),
//       y: (from: "ctrl-port-Instr", to: "dummy"),
//       w: 3, h: 4,
//       id: "imem",
//       fill: util.colors.green,
//       name: "Instruction\n Memory",
//       ports: (
//         west: (
//           (id: "A", name: "A"),
//           (id: "dummy"),
//           (id: "dummy2"),
//           (id: "RD", name: "RD"),
//         )
//       )
//     )
//     wire.wire(
//       "w-PC",
//       ("dp-port-PC", "imem-port-A"),
//       style: "zigzag",
//       directed: true
//     )
//     wire.wire(
//       "w-Instr1",
//       ("imem-port-RD", "dp-port-Instr"),
//       style: "zigzag",
//       zigzag-ratio: 30%,
//       directed: true
//     )
//     wire.wire(
//       "w-Instr2",
//       ("imem-port-RD", "ctrl-port-Instr"),
//       style: "zigzag",
//       zigzag-ratio: 30%,
//       directed: true
//     )
//     wire.intersection("w-Instr1.zig", radius: 2pt)
//     draw.content("w-Instr1.zig", "Instr", anchor: "south", padding: 4pt)
//     draw.content("w-PC.zig", "PC", anchor: "south-east", padding: 2pt)

//     draw.content("dmem.south-west", [*External Memories*], anchor: "north", padding: 10pt)
//   })

//   draw.line(name: "w-dp-clk",
//     "dp-port-clk",
//     (rel: (0, .5), to: ()),
//     (
//       rel: (-.5, 0),
//       to: (horizontal: "toplvl.west", vertical: ())
//     )
//   )
//   draw.content("w-dp-clk.end", "clk", anchor: "east", padding: 3pt)
  
//   draw.line(name: "w-dp-rst",
//     "dp-port-rst",
//     (
//       rel: (-.5, 0),
//       to: (horizontal: "toplvl.west", vertical: ())
//     )
//   )
//   draw.content("w-dp-rst.end", "rst", anchor: "east", padding: 3pt)
// })
