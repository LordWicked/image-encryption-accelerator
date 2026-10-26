package engine

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Reference values for the data phase, from the official Ascon reference (pyascon, NIST version).
  * Key = nonce = bytes 00..0f (as in AsconInitVectors), empty associated data,
  * plaintext = bytes 00..1f, i.e. two 16-byte blocks. The ciphertext words below match
  * the reference's full ascon_encrypt output.
  */
object AsconDataVectors {
  private def w(hex: String) = BigInt(hex, 16)

  /** Plaintext beats: 8 pixels per 64-bit word, pixel 0 in the lowest byte. */
  val plainBeats = Seq(w("0706050403020100"), w("0f0e0d0c0b0a0908"),
                       w("1716151413121110"), w("1f1e1d1c1b1a1918"))

  /** Expected ciphertext beats, in the same word format. */
  val cipherBeats = Seq(w("ee4aa4d289d270e7"), w("e37452ce8ea4d07c"),
                        w("97c4dc63e1d7ba81"), w("b1beeb0d6173780f"))

  /** State after both beats of block 0 are absorbed, before p8. */
  val absorbed0 = Seq(w("ee4aa4d289d270e7"), w("e37452ce8ea4d07c"), w("afe01d6bf4bcb26c"),
                      w("9216fd8172857e38"), w("f86a1691b9b11607"))

  /** State after p8 of block 0. */
  val afterP8Block0 = Seq(w("80d2c977f2c5ab91"), w("aea0f6117a696117"), w("d8c89a760645dfd9"),
                          w("1aa8cc9c4648a66b"), w("1954d9e133342d26"))

  /** State after both beats of block 1 are absorbed, before p8. */
  val absorbed1 = Seq(w("97c4dc63e1d7ba81"), w("b1beeb0d6173780f"), w("d8c89a760645dfd9"),
                      w("1aa8cc9c4648a66b"), w("1954d9e133342d26"))

  /** State after p8 of block 1, the last pixel block. This is the state before padding. */
  val afterP8Block1 = Seq(w("40454ff5aaa3b6e7"), w("635b7363f84abdab"), w("68997a37e52b9f0c"),
                          w("5d00da89290f5338"), w("f37b2b7291fb4fc8"))
}

class AsconDataSpec extends AnyFlatSpec with ChiselScalatestTester {
  import AsconInitVectors._
  import AsconDataVectors._
  import AsconTestUtil.hex

  def state(dut: Ascon): Seq[BigInt] = dut.io.dbg_state.map(_.peek().litValue)

  def check(dut: Ascon, expected: Seq[BigInt], what: String): Unit = {
    val got = state(dut)
    assert(got == expected, s"$what\n  got      ${hex(got)}\n  expected ${hex(expected)}")
  }

  /** Runs start + initialization, leaves the core in sWaitPixel. */
  def initialize(dut: Ascon): Unit = {
    dut.io.pixels_in.valid.poke(false.B)
    dut.io.enc_pixels_out.ready.poke(false.B)
    dut.io.key.poke(toCoreInput(keyBytes).U(128.W))
    dut.io.nonce.poke(toCoreInput(nonceBytes).U(128.W))
    dut.io.start.poke(true.B)
    dut.clock.step(1)
    dut.io.start.poke(false.B)
    dut.clock.step(12)
    check(dut, afterInit, "after initialization")
  }

  /** Sends one 64-bit beat and checks the ciphertext that comes out in the same cycle. */
  def sendBeat(dut: Ascon, i: Int): Unit = {
    dut.io.pixels_in.valid.poke(true.B)
    dut.io.pixels_in.bits.poke(plainBeats(i).U(64.W))
    dut.io.pixels_in.ready.expect(true.B, s"beat $i: core not ready")
    dut.io.enc_pixels_out.valid.expect(true.B, s"beat $i: output not valid")
    val got = dut.io.enc_pixels_out.bits.peek().litValue
    assert(got == cipherBeats(i), f"beat $i: ciphertext $got%016x, expected ${cipherBeats(i)}%016x")
    dut.clock.step(1)
  }

  "Ascon data phase" should "encrypt two blocks like the reference" in {
    test(new Ascon(numBlocks = 2)) { dut =>
      initialize(dut)
      dut.io.enc_pixels_out.ready.poke(true.B)

      // Block 0, with a one-cycle gap (valid low) between the two beats
      sendBeat(dut, 0)
      dut.io.pixels_in.valid.poke(false.B)
      dut.io.enc_pixels_out.valid.expect(false.B, "output valid without input")
      dut.clock.step(1)
      check(dut, cipherBeats(0) +: afterInit.tail, "gap: state changed without input")
      sendBeat(dut, 1)
      check(dut, absorbed0, "block 0 absorbed")

      // During p8 the core must not accept input
      dut.io.pixels_in.valid.poke(true.B)
      dut.io.pixels_in.ready.expect(false.B, "core accepts input during p8")
      dut.clock.step(8)
      check(dut, afterP8Block0, "block 0 after p8")

      // Block 1, with a two-cycle stall from the receiver (ready low)
      dut.io.pixels_in.bits.poke(plainBeats(2).U(64.W))
      dut.io.enc_pixels_out.ready.poke(false.B)
      dut.io.pixels_in.ready.expect(false.B, "core accepts input while receiver stalls")
      dut.clock.step(2)
      check(dut, afterP8Block0, "stall: state changed although receiver was not ready")
      dut.io.enc_pixels_out.ready.poke(true.B)
      sendBeat(dut, 2)
      sendBeat(dut, 3)
      check(dut, absorbed1, "block 1 absorbed")

      dut.clock.step(8)
      check(dut, afterP8Block1, "block 1 after p8 (state before padding)")

      // Block 1 was the last one (numBlocks = 2): the core must go to sPad,
      // not back to sWaitPixel, so it must not accept more pixels
      dut.io.pixels_in.valid.poke(true.B)
      dut.io.pixels_in.ready.expect(false.B, "core waits for pixels after the last block, lastBlock not set")
    }
  }
}
