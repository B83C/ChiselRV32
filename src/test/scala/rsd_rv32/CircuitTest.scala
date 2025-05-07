package rsd_rv32

import chisel3._
import chisel3.common._

import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers


class CircuitTest extends AnyFreeSpec with Matchers {
  "Comb adder" in {
    simulate(new Core()) {c=>
      // println(s"Testing ${a} + ${b}")
      // c.io.carry_in.poke(false)
      // c.io.a.poke(a.U)
      // c.io.b.poke(b.U)
      // c.io.out.expect((a + b)& ((1 << w) - 1))

      // c.io.carry_in.poke(true)
      // c.io.out.expect((a + b + 1)& ((1 << w) - 1))
    }
  }
}
