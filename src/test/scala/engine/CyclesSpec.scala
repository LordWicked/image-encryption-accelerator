package engine
import engine.algo.Ascon
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// counts the cycles from start to the last output, input always valid and output always ready,
// for the core alone and the whole Top with 1, 2 and 4 rounds per cycle.
// With the max clock from vivado this gives the throughput. Results go to generated/cycles.csv
class CyclesSpec extends AnyFlatSpec with ChiselScalatestTester {
  val blocks = 48 // a 16x16 RGB image
  val lines = scala.collection.mutable.ArrayBuffer(
    "part;roundsPerCycle;blocks;cycles;firstOut"
  )
  val cycles = scala.collection.mutable.Map[(String, Int), Int]()

  for (k <- Seq(1, 2, 4)) {
    it should s"count the cycles of the Ascon core alone, $k round(s) per cycle" in {
      test(new Ascon(blocks, k)) { dut =>
        dut.clock.setTimeout(0)
        dut.io.start.poke(true.B); dut.clock.step(1); dut.io.start.poke(false.B)
        dut.io.pixels_in.valid.poke(true.B);
        dut.io.enc_pixels_out.ready.poke(true.B)
        var outs = 0; var n = 1; var first = -1
        while (outs < blocks + 1) { // blocks + tag
          if (dut.io.enc_pixels_out.valid.peek().litToBoolean) {
            if (first < 0) first = n; outs += 1
          }
          dut.clock.step(1); n += 1
          assert(n < 100000, "timeout")
        }
        lines += s"core;$k;$blocks;$n;$first"
        cycles(("core", k)) = n
      }
    }
    it should s"count the cycles of the whole Top (bytes in and out), $k round(s) per cycle" in {
      test(new Top(16, 16, 3, 1, k)) { dut =>
        dut.clock.setTimeout(0)
        dut.io.start.poke(true.B); dut.clock.step(1); dut.io.start.poke(false.B)
        dut.io.in(0).valid.poke(true.B); dut.io.in(0).bits.poke(0x123456.U);
        dut.io.out(0).ready.poke(true.B)
        var outs = 0; var n = 1; var first = -1
        while (outs < blocks * 16 + 16) {
          if (dut.io.out(0).valid.peek().litToBoolean) {
            if (first < 0) first = n; outs += 1
          }
          dut.clock.step(1); n += 1
          assert(n < 100000, "timeout")
        }
        lines += s"top;$k;$blocks;$n;$first"
        cycles(("top", k)) = n
      }
    }
  }

  it should "need fewer core cycles with more rounds per cycle" in {
    assert(
      cycles(("core", 1)) > cycles(("core", 2)) && cycles(("core", 2)) > cycles(
        ("core", 4)
      )
    )
  }

  it should "write the results to generated/cycles.csv" in {
    new java.io.File("generated").mkdirs()
    java.nio.file.Files.write(
      java.nio.file.Paths.get("generated/cycles.csv"),
      (lines.mkString("\n") + "\n").getBytes
    )
  }
}
