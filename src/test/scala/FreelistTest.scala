import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import rsd_rv32.common._

class FreelistTest extends AnyFreeSpec with Matchers {
  implicit val p = new Parameters()
  "Free list" in {
    simulate(new FreeList(UInt(32.W), 16, 2)) { c=>
      //Necessary step to initialise? Did not work without it :/
      c.clock.step()

      c.io.deq_request.ready.poke(false)

      // Start enqueuing
      c.io.enq_request.bits(0).poke(0xDEADBEEFL)
      c.io.enq_request.bits(1).poke(0xFEEDDEADL)
      c.io.enq_request.valid.poke(true)
      c.clock.step()
      c.io.enq_request.valid.poke(false)

      c.io.enq_request.bits(1).poke(0xDEADBEEFL)
      c.io.enq_request.bits(0).poke(0xFEEDDEADL)
      c.io.enq_request.valid.poke(true)
      c.clock.step()
      c.io.enq_request.valid.poke(false)

      // Start dequeuing, should should dequeue two
      c.io.deq_request.ready.poke(true)
      c.io.deq_request.bits(0).expect(0xDEADBEEFL)
      c.io.deq_request.bits(1).expect(0xFEEDDEADL)
      c.io.deq_request.valid.expect(true.B)
      c.clock.step()
      c.io.deq_request.ready.poke(false)

      c.io.deq_request.ready.poke(true)
      c.io.deq_request.bits(1).expect(0xDEADBEEFL)
      c.io.deq_request.bits(0).expect(0xFEEDDEADL)
      c.io.deq_request.valid.expect(true.B)
      c.clock.step()
      c.io.deq_request.ready.poke(false)

      // End of dequeuing
      c.io.deq_request.valid.expect(false.B)
      c.clock.step()

      c.io.deq_request.valid.expect(false.B)
      c.clock.step()
    }
  }

  "Free list checkpoint" in {
    simulate(new FreeList(UInt(32.W), 16, 2)) { c=>
      // Necessary step to initialise? Did not work without it :/
      c.clock.step()

      // Initial condition
      c.io.deq_request.ready.poke(false)
      c.io.enq_request.valid.poke(false)

      // Start enqueuing
      c.io.enq_request.bits(0).poke(0xDEADBEEFL)
      c.io.enq_request.bits(1).poke(0xFEEDDEADL)
      c.io.enq_request.valid.poke(true)
      c.clock.step()
      c.io.enq_request.valid.poke(false)

      // Checkpoint now!
      c.io.checkpoint.poke(true)
      c.io.enq_request.bits(1).poke(0xDEADBEEFL)
      c.io.enq_request.bits(0).poke(0xFEEDDEADL)
      c.io.enq_request.valid.poke(true)
      c.clock.step()
      c.io.enq_request.valid.poke(false)
      c.io.checkpoint.poke(false)

      // Enqueue another one, should not affect the result
      c.io.enq_request.bits(0).poke(0xFEEDDAD0L)
      c.io.enq_request.bits(1).poke(0xDEADFEEDL)
      c.io.enq_request.valid.poke(true)
      c.clock.step()
      c.io.enq_request.valid.poke(false)

      // Restore now!
      c.io.restore.poke(true)
      c.clock.step()
      c.io.restore.poke(false)

      // Start dequeuing, should stop dequeuing after one entry (as expected)
      c.io.deq_request.ready.poke(true)
      c.io.deq_request.bits(0).expect(0xDEADBEEFL)
      c.io.deq_request.bits(1).expect(0xFEEDDEADL)
      c.io.deq_request.valid.expect(true.B)
      c.clock.step()
      c.io.deq_request.ready.poke(false)

      // End of dequeuing
      c.io.deq_request.valid.expect(false.B)
      c.clock.step()

      c.io.deq_request.valid.expect(false.B)
      c.clock.step()
    }
  }
}
