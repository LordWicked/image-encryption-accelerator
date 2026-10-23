package engine

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import _root_.engine.AsconInitSpec
 

// ---------------------------------------------------------------------------
// Test harnesses: the round functions are plain Scala functions that build
// hardware, so they need a small Module around them to be simulated.
// ---------------------------------------------------------------------------

/** Wraps any function on the 5-word state, e.g. substitutionLayer. */
class LayerHarness(f: Vec[UInt] => Vec[UInt]) extends Module {
  val io = IO(new Bundle {
    val in  = Input(Vec(5, UInt(64.W)))
    val out = Output(Vec(5, UInt(64.W)))
  })
  io.out := f(io.in)
}

/** Wraps a function that also takes a round index, e.g. compute. */
class RoundHarness(f: (Vec[UInt], UInt) => Vec[UInt]) extends Module {
  val io = IO(new Bundle {
    val in  = Input(Vec(5, UInt(64.W)))
    val rc  = Input(UInt(4.W))
    val out = Output(Vec(5, UInt(64.W)))
  })
  io.out := f(io.in, io.rc)
}

// ---------------------------------------------------------------------------
// Software helpers (pure Scala, no hardware)
// ---------------------------------------------------------------------------
object AsconTestUtil {
  val mask64: BigInt = (BigInt(1) << 64) - 1

  /** Ascon S-box as a lookup table, input x0 is the most significant bit. */
  val sBoxRef: Seq[Int] = Seq(
    0x04, 0x0b, 0x1f, 0x14, 0x1a, 0x15, 0x09, 0x02, 0x1b, 0x05, 0x08, 0x12, 0x1d, 0x03, 0x06, 0x1c,
    0x1e, 0x13, 0x07, 0x0e, 0x00, 0x0d, 0x11, 0x18, 0x10, 0x0c, 0x01, 0x19, 0x16, 0x0a, 0x0f, 0x17
  )

  /** Rotation pairs of the linear layer per word (from the spec). */
  val rotations: Seq[(Int, Int)] = Seq((19, 28), (61, 39), (1, 6), (10, 17), (7, 41))

  def rotr(x: BigInt, n: Int): BigInt = ((x >> n) | (x << (64 - n))) & mask64

  /** Builds five words in which bit column j holds the 5-bit value col(j). */
  def columnsToWords(col: Int => Int): Seq[BigInt] =
    (0 until 5).map { w =>
      (0 until 64).foldLeft(BigInt(0)) { (acc, j) =>
        if (((col(j) >> (4 - w)) & 1) == 1) acc.setBit(j) else acc
      }
    }

  /** Reads bit column j of five words back as a 5-bit value. */
  def wordsToColumn(words: Seq[BigInt], j: Int): Int =
    (0 until 5).foldLeft(0) { (acc, w) => (acc << 1) | (if (words(w).testBit(j)) 1 else 0) }

  def hex(s: Seq[BigInt]): String = s.map(v => f"$v%016x").mkString(" ")
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
class AsconPermutationSpec extends AnyFlatSpec with ChiselScalatestTester {
  import AsconTestUtil._

  def pokeState(port: Vec[UInt], s: Seq[BigInt]): Unit =
    port.zip(s).foreach { case (p, v) => p.poke(v.U(64.W)) }

  def peekState(port: Vec[UInt]): Seq[BigInt] =
    port.map(_.peek().litValue)

  "constantAddition" should "XOR the round constant into x2 and nothing else" in {
    test(new RoundHarness((s, rc) => AsconPermutation.constantAddition(rc, s))) { dut =>
      for (i <- 0 until 12) {
        val expectedConst = ((15 - i) << 4) | i // independent of the AsconCons table
        pokeState(dut.io.in, Seq.fill(5)(BigInt(0)))
        dut.io.rc.poke(i.U)
        val out = peekState(dut.io.out)
        assert(out == Seq(0, 0, expectedConst, 0, 0).map(BigInt(_)), s"round $i: ${hex(out)}")
      }
    }
  }

  "substitutionLayer" should "match the S-box table in every bit column" in {
    test(new LayerHarness(AsconPermutation.substitutionLayer)) { dut =>
      // 32 passes: in each pass every column gets a different input value,
      // and over all passes every column sees all 32 values.
      for (offset <- 0 until 32) {
        val col = (j: Int) => (j + offset) % 32
        pokeState(dut.io.in, columnsToWords(col))
        val out = peekState(dut.io.out)
        for (j <- 0 until 64) {
          val got = wordsToColumn(out, j)
          val exp = sBoxRef(col(j))
          assert(got == exp, f"column $j, input 0x${col(j)}%02x: got 0x$got%02x, expected 0x$exp%02x")
        }
      }
    }
  }

  "linearDiffusionLayer" should "spread a single bit to exactly three positions in its own word" in {
    test(new LayerHarness(AsconPermutation.linearDiffusionLayer)) { dut =>
      for (w <- 0 until 5; b <- 0 until 64) {
        val in = Seq.tabulate(5)(i => if (i == w) BigInt(0).setBit(b) else BigInt(0))
        pokeState(dut.io.in, in)
        val (r1, r2) = rotations(w)
        val expectedWord = in(w) ^ rotr(in(w), r1) ^ rotr(in(w), r2)
        val expected = Seq.tabulate(5)(i => if (i == w) expectedWord else BigInt(0))
        val out = peekState(dut.io.out)
        assert(out == expected, s"word x$w, bit $b:\n  got      ${hex(out)}\n  expected ${hex(expected)}")
      }
    }
  }

  // Reference values from pyascon (NIST version), see AsconInitVectors in AsconInitSpec.scala
  "compute" should "match the reference model for one round" in {
    val roundIndex = 0
    val before = AsconInitVectors.loaded
    val after  = AsconInitVectors.afterRound0

    test(new RoundHarness(AsconPermutation.compute)) { dut =>
      pokeState(dut.io.in, before)
      dut.io.rc.poke(roundIndex.U)
      val out = peekState(dut.io.out)
      assert(out == after, s"\n  got      ${hex(out)}\n  expected ${hex(after)}")
    }
  }
}