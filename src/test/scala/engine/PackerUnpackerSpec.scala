package engine

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

// wrapper module to connect packer directly to unpacker
class RoundTrip extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(8.W)))
    val out = Decoupled(UInt(8.W))
  })

  val packer = Module(new Packer)
  val unpacker = Module(new Unpacker)

  // link the chain together
  packer.io.in <> io.in
  unpacker.io.in <> packer.io.out
  io.out <> unpacker.io.out
}

class PackerUnpackerSpec extends AnyFlatSpec with ChiselScalatestTester {
  "Packer and Unpacker" should "pass a round trip test of 16 bytes" in {
    test(new RoundTrip).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

      // setup initial states
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step(1)

      // receiver is always ready to get data
      dut.io.out.ready.poke(true.B)

      // send 16 distinct bytes into the pipeline
      for (i <- 1 to 16) {
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.poke(i.U)
        dut.clock.step(1)
      }

      // stop sending data
      dut.io.in.valid.poke(false.B)

      // now read the 16 bytes coming out and check if they match
      for (i <- 1 to 16) {
        // wait until valid data pops out
        while (!dut.io.out.valid.peek().litToBoolean) {
          dut.clock.step(1)
        }

        // expect the exact same byte we sent
        dut.io.out.bits.expect(i.U)
        dut.clock.step(1)
      }
    }
  }
}