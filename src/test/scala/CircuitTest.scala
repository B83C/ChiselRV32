import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CircuitTest extends AnyFreeSpec with Matchers {
  "Reg adder" in {
    val widths = Seq(4, 8, 12, 16) 
    for (w <- widths) {
      simulate(new Adder(w)) {c=>
        val a = scala.util.Random.nextInt(1 << w)
        val b = scala.util.Random.nextInt(1 << w)
        println(s"Testing ${a} + ${b}")
        c.io.carry_in.poke(false)
        c.io.a.poke(a.U)
        c.io.b.poke(b.U)
        c.io.add.poke(true)
        c.clock.step()
        c.io.add.poke(false)
        while(c.io.busy.peek().litToBoolean) {
          c.clock.step()
        }
        c.io.out.expect((a + b)& ((1 << w) - 1))

        c.io.carry_in.poke(true)
        c.io.add.poke(true)
        c.clock.step()
        c.io.add.poke(false)
        while(c.io.busy.peek().litToBoolean) {
          c.clock.step()
        }
        c.io.out.expect((a + b + 1)& ((1 << w) - 1))
      }
    }
  }
  "Comb adder" in {
    val widths = Seq(4, 8, 12, 16) 
    for (w <- widths) {
      simulate(new AdderComb(w)) {c=>
        val a = scala.util.Random.nextInt(1 << w)
        val b = scala.util.Random.nextInt(1 << w)
        println(s"Testing ${a} + ${b}")
        c.io.carry_in.poke(false)
        c.io.a.poke(a.U)
        c.io.b.poke(b.U)
        c.io.out.expect((a + b)& ((1 << w) - 1))

        c.io.carry_in.poke(true)
        c.io.out.expect((a + b + 1)& ((1 << w) - 1))
      }
    }
  }
}
