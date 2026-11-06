package engine

import chisel3._
import chiseltest._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TopSpec extends AnyFlatSpec with ChiselScalatestTester {
  "Top" should "generate Verilog" in {
    // fails if any wire in the design is left without a value
    val verilog = ChiselStage.emitSystemVerilog(new Top())
    assert(verilog.contains("module Top"))
  }

  it should "encrypt a byte stream like the reference" in {
    import AsconInitVectors._
    import AsconDataVectors._
    import AsconTagVectors._

    // the same 2 blocks as AsconDataSpec / AsconTagSpec, but as single bytes
    // going through Packer -> Ascon -> Unpacker
    def toBytes(beats: Seq[BigInt]): Seq[Int] =
      beats.flatMap(beat => (0 until 8).map(i => ((beat >> (8 * i)) & 0xff).toInt))
    val plain = toBytes(plainBeats)
    val expected = toBytes(cipherBeats) ++ toBytes(tagBeats)

    test(new Top(numBlocks = 2)) { dut =>
      dut.io.key.poke(toCoreInput(keyBytes).U(128.W))
      dut.io.nonce.poke(toCoreInput(nonceBytes).U(128.W))
      dut.io.start.poke(true.B)
      dut.clock.step(1)
      dut.io.start.poke(false.B)
      dut.io.out.ready.poke(true.B)

      val result = ArrayBuffer[Int]()
      var sent = 0
      var cycles = 0
      while (result.length < expected.length) {
        dut.io.in.valid.poke((sent < plain.length).B)
        dut.io.in.bits.poke(if (sent < plain.length) plain(sent).U else 0.U)
        if (sent < plain.length && dut.io.in.ready.peek().litToBoolean) sent += 1
        if (dut.io.out.valid.peek().litToBoolean) result += dut.io.out.bits.peek().litValue.toInt
        dut.clock.step(1)
        cycles += 1
        assert(cycles < 1000, s"timeout, got ${result.length} of ${expected.length} bytes")
      }

      assert(result == expected, s"\n  got      ${result.map(b => f"$b%02x").mkString}\n  expected ${expected.map(b => f"$b%02x").mkString}")
    }
  }
}
