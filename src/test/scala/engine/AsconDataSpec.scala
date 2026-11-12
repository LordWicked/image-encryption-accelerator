package engine

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Reference values for the data phase, from the official Ascon reference (pyascon, NIST version).
  * Key = nonce = bytes 00..0f (as in AsconInitVectors), empty associated data,
  * plaintext = bytes 00..1f, i.e. two 16-byte blocks. The ciphertext below matches
  * the reference's full ascon_encrypt output.
  */
object AsconDataVectors {
  private def w(hex: String) = BigInt(hex, 16)

  /** Joins two 64-bit words into one 128-bit transfer: low word = x0, high word = x1. */
  def block(x0: BigInt, x1: BigInt): BigInt = (x1 << 64) | x0

  /** Plaintext blocks: 16 pixels per transfer, pixel 0 in bits 7..0.
    * Equal to the 16 plaintext bytes read as one little-endian integer.
    */
  val plainBlocks = Seq(
    block(w("0706050403020100"), w("0f0e0d0c0b0a0908")),
    block(w("1716151413121110"), w("1f1e1d1c1b1a1918"))
  )

  /** Expected ciphertext blocks, in the same format. */
  val cipherBlocks = Seq(
    block(w("ee4aa4d289d270e7"), w("e37452ce8ea4d07c")),
    block(w("97c4dc63e1d7ba81"), w("b1beeb0d6173780f"))
  )

  /** State after block 0 is absorbed, before p8. */
  val absorbed0 = Seq(w("ee4aa4d289d270e7"), w("e37452ce8ea4d07c"), w("afe01d6bf4bcb26c"),
                      w("9216fd8172857e38"), w("f86a1691b9b11607"))

  /** State after p8 of block 0. */
  val afterP8Block0 = Seq(w("80d2c977f2c5ab91"), w("aea0f6117a696117"), w("d8c89a760645dfd9"),
                          w("1aa8cc9c4648a66b"), w("1954d9e133342d26"))

  /** State after block 1 is absorbed, before p8. */
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

  val roundsPerCycleConfigs = Seq(1, 2, 4)
  
  def state(dut: Ascon): Seq[BigInt] = dut.io.dbg_state.map(_.peek().litValue)

  def check(dut: Ascon, expected: Seq[BigInt], what: String): Unit = {
    val got = state(dut)
    assert(got == expected, s"$what\n  got      ${hex(got)}\n  expected ${hex(expected)}")
  }

  /** Runs start + initialization, leaves the core in sWaitPixel. */
  def initialize(dut: Ascon, r: Int): Unit = {
    dut.io.pixels_in.valid.poke(false.B)
    dut.io.enc_pixels_out.ready.poke(false.B)
    dut.io.key.poke(toCoreInput(keyBytes).U(128.W))
    dut.io.nonce.poke(toCoreInput(nonceBytes).U(128.W))
    dut.io.start.poke(true.B)
    dut.clock.step(1)
    dut.io.start.poke(false.B)
    dut.clock.step(12 / r)
    check(dut, afterInit, "after initialization")
  }

  /** Sends one 128-bit block and checks the ciphertext that comes out in the same cycle. */
  def sendBlock(dut: Ascon, i: Int): Unit = {
    dut.io.pixels_in.valid.poke(true.B)
    dut.io.pixels_in.bits.poke(plainBlocks(i).U(128.W))
    dut.io.pixels_in.ready.expect(true.B, s"block $i: core not ready")
    dut.io.enc_pixels_out.valid.expect(true.B, s"block $i: output not valid")
    val got = dut.io.enc_pixels_out.bits.peek().litValue
    assert(got == cipherBlocks(i), f"block $i: ciphertext $got%032x, expected ${cipherBlocks(i)}%032x")
    dut.clock.step(1)
  }


  for (r <- roundsPerCycleConfigs) {
    "Ascon data phase" should s"encrypt two blocks like the reference (roundsPerCycle = $r)" in {
      test(new Ascon(numBlocks = 2, roundsPerCycle = r)) { dut =>
        initialize(dut, r)
        dut.io.enc_pixels_out.ready.poke(true.B)

        // Two cycles without input (valid low): nothing may happen
        dut.io.pixels_in.valid.poke(false.B)
        dut.io.enc_pixels_out.valid.expect(false.B, "output valid without input")
        dut.clock.step(2)
        check(dut, afterInit, "gap: state changed without input")

        // Block 0
        sendBlock(dut, 0)
        check(dut, absorbed0, "block 0 absorbed")

        // During p8 the core must not accept input
        dut.io.pixels_in.valid.poke(true.B)
        dut.io.pixels_in.ready.expect(false.B, "core accepts input during p8")
        dut.clock.step(8 / r)
        check(dut, afterP8Block0, "block 0 after p8")

        // Block 1, with a two-cycle stall from the receiver (ready low)
        dut.io.pixels_in.bits.poke(plainBlocks(1).U(128.W))
        dut.io.enc_pixels_out.ready.poke(false.B)
        dut.io.pixels_in.ready.expect(false.B, "core accepts input while receiver stalls")
        dut.clock.step(2)
        check(dut, afterP8Block0, "stall: state changed although receiver was not ready")
        dut.io.enc_pixels_out.ready.poke(true.B)
        sendBlock(dut, 1)
        check(dut, absorbed1, "block 1 absorbed")

        dut.clock.step(8 / r)
        check(dut, afterP8Block1, "block 1 after p8 (state before padding)")

        // Block 1 was the last one (numBlocks = 2): the core must go to sPad,
        // not back to sWaitPixel, so it must not accept more pixels
        dut.io.pixels_in.valid.poke(true.B)
        dut.io.pixels_in.ready.expect(false.B, "core waits for pixels after the last block, lastBlock not set")
      }
    }
  }
}