import chisel3._
import chisel3.util._
// import chisel3.simulator.EphemeralSimulator._
import rsd_rv32.common._
import rsd_rv32.scheduler._
import rsd_rv32.execution._
import chisel3.experimental.VecLiterals._

// import chiseltest._

import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.EphemeralSimulator._
import chisel3.experimental.BundleLiterals._

import rsd_rv32._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import chiseltest.ChiselScalatestTester
import chiseltest.simulator.WriteVcdAnnotation


// import chipmunk._

// import tywaves.simulator.simulatorSettings._
// // import tywaves.simulator.ParametricSimulator._
// import tywaves.simulator.TywavesSimulator._

class CoreTest extends AnyFreeSpec with Matchers {
// class CoreTest extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = new Parameters()

  // "core" should "Core Fetch instructions without issues" in {
  "Core Fetch instructions without issues" in {
    // test(new Core).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
    simulate(new Core) { c =>
      // c.mem.memInside.write(0.U, Vec.Lit(0xa.U(8.W), 0xbb.U(8.W), 0xa.U(8.W), 0xbb.U(8.W)))
      c.io.rst.poke(true.B)
      c.clock.step()
      // c.io.rst.poke(false.B)
      println("reset!")
      for(_ <- 0 until 10) {
        c.clock.step()
      }
    }
    // simulate(
    //   new Core(), // The module to simulate
    //   settings = Seq(),
    //   simName = "Name_of_the_simulation", // used to name the directory in test_run_dir
    // ) {
    //   dut => // Body of the simulation using the PeekPokeAPI
    //   // dut.clock.step()
    // }
    // simulate(new Core()) { c =>
    //   c.clock.step()
    // }
  }
}
