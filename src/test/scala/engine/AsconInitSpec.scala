package engine

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Expected values from the official Ascon reference (pyascon, NIST SP 800-232 version).
  * Key = nonce = bytes 00 01 02 ... 0f, the same inputs as NIST KAT "Count = 1".
  * The reference reproduces that KAT's tag 4427d64b8e1e1451fc445960f0839bb0.
  */
object AsconInitVectors {
  private def w(hex: String) = BigInt(hex, 16)

  val keyBytes: Seq[Int]   = 0 until 16
  val nonceBytes: Seq[Int] = 0 until 16

  /** State right after loading IV, key and nonce. */
  val loaded = Seq(w("00001000808c0001"), w("0706050403020100"), w("0f0e0d0c0b0a0908"),
                   w("0706050403020100"), w("0f0e0d0c0b0a0908"))

  /** State after the first round (round index 0) of p12. */
  val afterRound0 = Seq(w("7651a37db8c64841"), w("3631a1237df00253"), w("3fffffffffffff74"),
                        w("3c38900488a461b3"), w("1c1c1c1c1c1c1c1c"))

  /** State after p12, key XOR into x3||x4 and domain separation (empty AD). */
  val afterInit = Seq(w("e94ca1d68ad071e7"), w("ec7a5fc285aed974"), w("afe01d6bf4bcb26c"),
                      w("9216fd8172857e38"), w("f86a1691b9b11607"))

  /** Byte-order convention: 16 bytes -> two little-endian 64-bit words, first word on top.
    * This is how the testbench / packer hands key and nonce to the core.
    */
  def toCoreInput(bytes: Seq[Int]): BigInt = bytes.zipWithIndex.map { case (v, i) => BigInt(v) << (8 * i) }.sum
}

class AsconInitSpec extends AnyFlatSpec with ChiselScalatestTester {
  import AsconInitVectors._
  import AsconTestUtil.hex

  def state(dut: Ascon): Seq[BigInt] = dut.io.dbg_state.map(_.peek().litValue)

  def check(dut: Ascon, expected: Seq[BigInt], what: String): Unit = {
    val got = state(dut)
    assert(got == expected, s"$what\n  got      ${hex(got)}\n  expected ${hex(expected)}")
  }

  "Ascon sInit" should "produce the reference state after initialization" in {
    test(new Ascon()) { dut =>
      dut.io.pixels_in.valid.poke(false.B)
      dut.io.enc_pixels_out.ready.poke(false.B)

      // Cycle 0: start pulse, the core loads IV, key and nonce at the clock edge
      dut.io.key.poke(toCoreInput(keyBytes).U(128.W))
      dut.io.nonce.poke(toCoreInput(nonceBytes).U(128.W))
      dut.io.start.poke(true.B)
      dut.clock.step(1)
      dut.io.start.poke(false.B)
      check(dut, loaded, "after loading (IV, key/nonce byte order)")

      // Change the inputs: the core must keep using keyReg from now on
      dut.io.key.poke(0.U)
      dut.io.nonce.poke(0.U)

      // Round 0 of p12: narrows down a failure to the round logic or the counter
      dut.clock.step(1)
      check(dut, afterRound0, "after round 0")

      // Remaining 11 rounds, then key XOR and domain separation
      dut.clock.step(11)
      check(dut, afterInit, "after initialization (p12, key, domain separation)")

      // The FSM has left sInit, so the state must stay unchanged
      dut.clock.step(3)
      check(dut, afterInit, "state changed after init, FSM did not leave sInit")
    }
  }
}
