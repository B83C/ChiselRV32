#import "imports.typ": *

#circuit({
  element.group(name: "Toplevel", {
    element.group(name: "Frontend", id: "frontend", {
      element.block(
        x: 0, y: 0, w: 8, h: 4,
        id: "fetch",
        fill: util.colors.pink,
        name: "Fetch",
        ports: (
        ),
        ports-margins: (
        )
      )
      element.block(
      x: (rel: 1, to: "fetch.east"),
      y: (rel: 0, to: "fetch.north"),
      w: 3, h: 4,
        id: "decode",
        fill: util.colors.pink,
        name: "Decode",
        ports: (
        ),
        ports-margins: (
          north: (0%, 0%),
          west: (0%, 70%)
        )
      )
      element.block(
        x: 0, y: -5, w: 8, h: 4,
        id: "bp",
        fill: util.colors.pink,
        name: "Branch Predictor",
        ports: (
        ),
        ports-margins: (
          north: (0%, 0%),
          west: (0%, 70%)
        )
      )      
    })
    element.group(name: "Scheduling", id: "scheduler",
    {
      element.block(
        x: (rel: 3, to: "decode.east"),
        y: 0,
        w: 4, h:4,
        id: "idk",
        fill: util.colors.pink,
        name: "Fetch",
        ports: (
        ),
        ports-margins: (
        )
      )
    })
  })
})
#circuit({
  element.group(name: "Toplevel", {
    element.group(
      name: "test", {
      element.block(
        x: 0, y: 0, w: 8, h: 4,
        id: "dp",
        fill: util.colors.pink,
        name: "Datapath",
        ports: (
          north: (
            (id: "clk", clock: true, small: true),
            (id: "Zero"),
            (id: "Regsrc"),
            (id: "PCSrc"),
            (id: "ResultSrc"),
            (id: "ALUControl"),
            (id: "ImmSrc"),
            (id: "RegWrite"),
            (id: "dummy")
          ),
          east: (
            (id: "PC", name: "PC"),
            (id: "Instr", name: "Instr"),
            (id: "ALUResult", name: "ALUResult"),
            (id: "dummy"),
            (id: "WriteData", name: "WriteData"),
            (id: "ReadData", name: "ReadData"),
          ),
          west: (
            (id: "rst"),
          )
        ),
        ports-margins: (
          north: (0%, 0%),
          west: (0%, 70%)
        )
      )
        
      }
    )
  })
})

#circuit({
  element.group(id: "toplvl", name: "Toplevel", {
    element.group(
      id: "proc",
      name: "Processor",
      padding: 1.5em,
      stroke: (dash: "dashed"),
      {
      element.block(
        x: 0, y: 0, w: 8, h: 4,
        id: "dp",
        fill: util.colors.pink,
        name: "Datapath",
        ports: (
          north: (
            (id: "clk", clock: true, small: true),
            (id: "Zero"),
            (id: "Regsrc"),
            (id: "PCSrc"),
            (id: "ResultSrc"),
            (id: "ALUControl"),
            (id: "ImmSrc"),
            (id: "RegWrite"),
            (id: "dummy")
          ),
          east: (
            (id: "PC", name: "PC"),
            (id: "Instr", name: "Instr"),
            (id: "ALUResult", name: "ALUResult"),
            (id: "dummy"),
            (id: "WriteData", name: "WriteData"),
            (id: "ReadData", name: "ReadData"),
          ),
          west: (
            (id: "rst"),
          )
        ),
        ports-margins: (
          north: (0%, 0%),
          west: (0%, 70%)
        )
      )
      
      element.block(
        x: 0, y: 7, w: 8, h: 3,
        id: "ctrl",
        fill: util.colors.orange,
        name: "Controller",
        ports: (
          east: (
            (id: "Instr", name: "Instr"),
          ),
          south: (
            (id: "dummy"),
            (id: "Zero"),
            (id: "Regsrc"),
            (id: "PCSrc"),
            (id: "ResultSrc"),
            (id: "ALUControl"),
            (id: "ImmSrc"),
            (id: "RegWrite"),
            (id: "MemWrite")
          )
        ),
        ports-margins: (
          south: (0%, 0%)
        )
      )
      wire.wire(
        "w-Zero",
        ("dp-port-Zero", "ctrl-port-Zero"),
        name: "Zero",
        name-pos: "start",
        directed: true
      )
      for p in ("Regsrc", "PCSrc", "ResultSrc", "ALUControl", "ImmSrc", "RegWrite") {
        wire.wire(
          "w-" + p,
          ("ctrl-port-"+p, "dp-port-"+p),
          name: p,
          name-pos: "start",
          directed: true
        )
      }

      draw.content(
        (rel: (0, 1em), to: "ctrl.north"),
        [*RISCV single*],
        anchor: "south"
      )
    })
    
    element.block(
      x: (rel: 4.5, to: "dp.east"),
      y: (from: "dp-port-ReadData", to: "RD"),
      w: 3, h: 4,
      id: "dmem",
      fill: util.colors.green,
      name: "Data\n Memory",
      ports: (
        north: (
          (id: "clk", clock: true, small: true),
          (id: "WE", name: "WE")
        ),
        west: (
          (id: "dummy"),
          (id: "dummy"),
          (id: "A", name: "A"),
          (id: "dummy"),
          (id: "WD", name: "WD"),
          (id: "RD", name: "RD"),
        )
      ),
      ports-margins: (
        north: (0%, 10%)
      )
    )
    wire.wire(
      "w-DataAddr",
      ("dp-port-ALUResult", "dmem-port-A"),
      name: "DataAddr",
      name-pos: "end",
      directed: true
    )
    wire.wire(
      "w-WriteData",
      ("dp-port-WriteData", "dmem-port-WD"),
      name: "WriteData",
      name-pos: "end",
      directed: true
    )
    wire.wire(
      "w-ReadData",
      ("dmem-port-RD", "dp-port-ReadData"),
      name: "ReadData",
      name-pos: "end",
      reverse: true,
      directed: true
    )
    wire.wire(
      "w-MemWrite",
      ("ctrl-port-MemWrite", "dmem-port-WE"),
      style: "zigzag",
      name: "MemWrite",
      name-pos: "start",
      zigzag-dir: "horizontal",
      zigzag-ratio: 80%,
      directed: true
    )
    wire.stub(
      "dmem-port-clk", "north",
      name: "clk", length: 3pt
    )

    element.block(
      x: (rel: 3.5, to: "dp.east"),
      y: (from: "ctrl-port-Instr", to: "dummy"),
      w: 3, h: 4,
      id: "imem",
      fill: util.colors.green,
      name: "Instruction\n Memory",
      ports: (
        west: (
          (id: "A", name: "A"),
          (id: "dummy"),
          (id: "dummy2"),
          (id: "RD", name: "RD"),
        )
      )
    )
    wire.wire(
      "w-PC",
      ("dp-port-PC", "imem-port-A"),
      style: "zigzag",
      directed: true
    )
    wire.wire(
      "w-Instr1",
      ("imem-port-RD", "dp-port-Instr"),
      style: "zigzag",
      zigzag-ratio: 30%,
      directed: true
    )
    wire.wire(
      "w-Instr2",
      ("imem-port-RD", "ctrl-port-Instr"),
      style: "zigzag",
      zigzag-ratio: 30%,
      directed: true
    )
    wire.intersection("w-Instr1.zig", radius: 2pt)
    draw.content("w-Instr1.zig", "Instr", anchor: "south", padding: 4pt)
    draw.content("w-PC.zig", "PC", anchor: "south-east", padding: 2pt)

    draw.content("dmem.south-west", [*External Memories*], anchor: "north", padding: 10pt)
  })

  draw.line(name: "w-dp-clk",
    "dp-port-clk",
    (rel: (0, .5), to: ()),
    (
      rel: (-.5, 0),
      to: (horizontal: "toplvl.west", vertical: ())
    )
  )
  draw.content("w-dp-clk.end", "clk", anchor: "east", padding: 3pt)
  
  draw.line(name: "w-dp-rst",
    "dp-port-rst",
    (
      rel: (-.5, 0),
      to: (horizontal: "toplvl.west", vertical: ())
    )
  )
  draw.content("w-dp-rst.end", "rst", anchor: "east", padding: 3pt)
})
