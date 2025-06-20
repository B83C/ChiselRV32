package rsd_rv32.frontend

import chisel3._
import chisel3.util._
import rsd_rv32.common._
import rsd_rv32.scheduler._

class Decode_IO(implicit p: Parameters) extends CustomBundle {
  // from IF
  val id_uop = Flipped(Decoupled(Vec(p.CORE_WIDTH, Valid(new IF_ID_uop()))))
  
  // to Rename
  val rename_uop = Decoupled(Vec(p.CORE_WIDTH, Valid(new ID_RENAME_uop())))
  
  // with ROB
  val rob_controlsignal = Flipped(new ROBControlSignal) //来自于ROB的控制信号
}


class DecodeUnit(implicit p: Parameters) extends CustomModule {
  val io = IO(new Decode_IO())

  //flush信号
  val should_flush = io.rob_controlsignal.shouldFlush
  
  // Decode always works, no doubt
  val input_valid = io.id_uop.valid
  val operation_ready = true.B
  val downstream_ready = io.rename_uop.ready
  val ack = operation_ready && io.rename_uop.ready
  
  io.id_uop.ready := ack

  var has_valid_instruction = false.B
  withReset(reset.asBool || should_flush)  {
    io.rename_uop.bits := RegEnable(VecInit(io.id_uop.bits.map{ case id => {
      val to_rename = Wire(Valid(new ID_RENAME_uop)) 
      (to_rename: Data).waiveAll :<>= (id: Data).waiveAll

      // _后缀表示opcode被截
      to_rename.bits.instr_ := id.bits.instr(31, 7)

      val dis = Instr.disassemble(id.bits.instr)
      val instr_type = WireDefault(InstrType(), InstrType.ALU)
      val opr1_sel = WireDefault(OprSel(), OprSel.Z)
      val opr2_sel = WireDefault(OprSel(), OprSel.Z)
    
      val instr_class = WireDefault(IType.Invalid)
      import IType._
    
      to_rename.bits.instr_type := instr_type
      to_rename.bits.opr1_sel := opr1_sel
      to_rename.bits.opr2_sel := opr2_sel
      to_rename.bits.rs1 := dis.rs1
      to_rename.bits.rs2 := dis.rs2
   
      switch(dis.opcode) {
        is("b0110011".U) {
          when(dis.funct7 === "b0000001".U) {
            when(dis.funct3(2) === 0.U) {
              instr_type := InstrType.MUL
            }.elsewhen(dis.funct3(2) === 1.U) {
              instr_type := InstrType.DIV_REM
            }.otherwise {
              instr_type := InstrType.ALU
            }
          }.otherwise {
            instr_type := InstrType.ALU
          }
          //immExt := 0.U
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.REG
          instr_class := R
        }

        is("b0010011".U) { //REG-IMM-ALU
          instr_type := InstrType.ALU
          //immExt := Cat(Fill(20, immI(11)), immI)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.IMM
          instr_class := I
        }

        is("b0000011".U) { //Load
          instr_type := InstrType.LD
          //immExt := Cat(Fill(20, immI(11)), immI)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.IMM
          instr_class := I
        }

        is("b0100011".U) { //Store
          instr_type := InstrType.ST
          //immExt := Cat(Fill(20, immS(11)), immS)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.REG
          instr_class := S
        }

        is("b1100011".U) { //Branch
          instr_type := InstrType.Branch
          //immExt := Cat(Fill(19, immB(12)), immB)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.REG
          instr_class := B
        }

        is("b1101111".U) { //JAL
          instr_type := InstrType.Jump
          //immExt := Cat(Fill(11, immJ(20)), immJ)
          opr1_sel := OprSel.PC
          opr2_sel := OprSel.IMM
          instr_class := J
        }

        is("b1100111".U) { //JALR
          instr_type := InstrType.Jump
          //immExt := Cat(Fill(20, immI(11)), immI)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.IMM
          instr_class := I
        }

        is("b1110011".U) { //CSR
          instr_type := InstrType.CSR
          //immExt := Cat(Fill(20, immI(11)), immI)
          opr1_sel := OprSel.REG
          opr2_sel := OprSel.IMM
          instr_class := I
        }

        is("b0110111".U) { //LUI
          instr_type := InstrType.ALU
          //immExt := immU
          opr1_sel := OprSel.IMM
          opr2_sel := OprSel.Z
          instr_class := U
        }

        is("b0010111".U) { //AUIPC
          instr_type := InstrType.ALU
          //immExt := immU
          opr1_sel := OprSel.PC
          opr2_sel := OprSel.IMM
          instr_class := U
        }
      }
    
      // NOP 优化
      val is_nop = instr_type === InstrType.ALU && dis.rd === 0.U && dis.rs1 === 0.U && dis.rs2 === 0.U
      val instr_valid = id.valid && instr_class =/= IType.Invalid && !is_nop
    
      has_valid_instruction |= instr_valid
      
      to_rename.bits.rd.bits := dis.rd
      to_rename.bits.rd.valid := instr_class.isOneOf(R, I, U, J) && dis.rd =/= 0.U && instr_valid

      // Debugging
      to_rename.bits.debug := id.bits.debug

      to_rename.valid := instr_valid && id.valid

      to_rename
    }}), ack)
    io.rename_uop.valid := RegEnable(io.id_uop.valid && has_valid_instruction, false.B, ack)
  }
}
