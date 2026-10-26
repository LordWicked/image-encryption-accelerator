package engine

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Reference tag for key = nonce = bytes 00..0f, empty associated data and
  * plaintext bytes 00..1f (the same inputs as AsconDataVectors).
  * From the official Ascon reference (pyascon, NIST version): tag = a28657f6e82fe53d08b09eff9330bd2b
  */
object AsconTagVectors {
  /** The tag as two 64-bit beats in the core's word format (little-endian bytes). */
  val tagBeats = Seq(BigInt("3de52fe8f65786a2", 16), BigInt("2bbd3093ff9eb008", 16))
}

class AsconTagSpec extends AnyFlatSpec with ChiselScalatestTester {
  import AsconInitVectors._
  import AsconDataVectors._
  import AsconTagVectors._
  import AsconTestUtil.hex

  def check(dut: Ascon, expected: Seq[BigInt], what: String): Unit = {
    val got = dut.io.dbg_state.map(_.peek().litValue)
    assert(got == expected, s"$what\n  got      ${hex(got)}\n  expected ${hex(expected)}")
  }

  def expectWord(dut: Ascon, expected: BigInt, what: String): Unit = {
    dut.io.enc_pixels_out.valid.expect(true.B, s"$what: output not valid")
    val got = dut.io.enc_pixels_out.bits.peek().litValue
    assert(got == expected, f"$what: got $got%016x, expected $expected%016x")
  }

  /** One full encryption of the two reference blocks, from start pulse to tag. */
  def encryptAndCheckTag(dut: Ascon, run: Int): Unit = {
    // Start and initialization
    dut.io.pixels_in.valid.poke(false.B)
    dut.io.enc_pixels_out.ready.poke(false.B)
    dut.io.key.poke(toCoreInput(keyBytes).U(128.W))
    dut.io.nonce.poke(toCoreInput(nonceBytes).U(128.W))
    dut.io.start.poke(true.B)
    dut.clock.step(1)
    dut.io.start.poke(false.B)
    dut.clock.step(12)
    check(dut, afterInit, s"run $run: after initialization")

    // Two blocks of pixels, each followed by p8
    dut.io.enc_pixels_out.ready.poke(true.B)
    for (block <- 0 until 2) {
      for (half <- 0 until 2) {
        val i = 2 * block + half
        dut.io.pixels_in.valid.poke(true.B)
        dut.io.pixels_in.bits.poke(plainBeats(i).U(64.W))
        dut.io.pixels_in.ready.expect(true.B, s"run $run, beat $i: core not ready")
        expectWord(dut, cipherBeats(i), s"run $run, ciphertext beat $i")
        dut.clock.step(1)
      }
      dut.io.pixels_in.valid.poke(false.B)
      dut.clock.step(8)
    }
    check(dut, afterP8Block1, s"run $run: state before padding")

    // sPad (1 cycle) and sFinal (12 cycles), no output in between
    dut.io.enc_pixels_out.ready.poke(false.B)
    for (c <- 0 until 13) {
      dut.io.enc_pixels_out.valid.expect(false.B, s"run $run: output valid during padding/finalization (cycle $c)")
      dut.clock.step(1)
    }

    // Tag word 1 must stay on the output while the receiver stalls
    expectWord(dut, tagBeats(0), s"run $run, tag word 1")
    dut.clock.step(2)
    expectWord(dut, tagBeats(0), s"run $run, tag word 1 after stall")

    // Receiver ready: both tag words go out
    dut.io.enc_pixels_out.ready.poke(true.B)
    expectWord(dut, tagBeats(0), s"run $run, tag word 1")
    dut.clock.step(1)
    expectWord(dut, tagBeats(1), s"run $run, tag word 2")
    dut.clock.step(1)

    // Done: core is back in sIdle, nothing more on the output
    dut.io.enc_pixels_out.valid.expect(false.B, s"run $run: output still valid after the tag")
    dut.io.pixels_in.valid.poke(true.B)
    dut.io.pixels_in.ready.expect(false.B, s"run $run: core accepts pixels without a new start")
    dut.io.pixels_in.valid.poke(false.B)
    dut.io.enc_pixels_out.ready.poke(false.B)
    dut.clock.step(1)
  }

  "Ascon" should "produce the reference tag, also for a second image after the first" in {
    test(new Ascon(numBlocks = 2)) { dut =>
      // Run 1: normal encryption
      encryptAndCheckTag(dut, run = 1)
      // Run 2: same inputs again. Only passes if blockCount, lastBlock and
      // secondHalf were reset correctly after the first image.
      encryptAndCheckTag(dut, run = 2)
    }
  }
}
