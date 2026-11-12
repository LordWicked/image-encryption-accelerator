package engine

import chisel3._
import chiseltest._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TopSpec extends AnyFlatSpec with ChiselScalatestTester {
  import AsconInitVectors._

  val roundsPerCycleConfigs = Seq(1, 2, 4)

  // the RGB pixels that give these plaintext bytes: 3 bytes per pixel in RGB,
  // and R = G = B in grayscale, which the formatter keeps as the same byte
  def toPixels(plain: Seq[Int], channels: Int): Seq[Int] =
    if (channels == 1) plain.map(b => (b << 16) | (b << 8) | b)
    else plain.grouped(3).map { case Seq(r, g, b) => (r << 16) | (g << 8) | b }.toSeq

  // streams one image per lane through the whole Top, returns ciphertext + tag of each
  // image i gets the base nonce + i, and its output is not taken before cycle stall(i)
  def encryptAll(dut: Top, plains: Seq[Seq[Int]], channels: Int, stall: Seq[Int]): Seq[Seq[Int]] = {
    val lanes = plains.indices
    val pixels = plains.map(toPixels(_, channels))
    dut.io.key.poke(toCoreInput(keyBytes).U(128.W))
    for (i <- lanes) dut.io.nonce(i).poke((toCoreInput(nonceBytes) + i).U(128.W))
    dut.io.start.poke(true.B)
    dut.clock.step(1)
    dut.io.start.poke(false.B)

    val results = plains.map(_ => ArrayBuffer[Int]())
    val sent = Array.fill(plains.length)(0)
    var cycles = 0
    while (lanes.exists(i => results(i).length < plains(i).length + 16)) {
      for (i <- lanes) {
        val ready = cycles >= stall(i)
        dut.io.out(i).ready.poke(ready.B)
        dut.io.in(i).valid.poke((sent(i) < pixels(i).length).B)
        dut.io.in(i).bits.poke(if (sent(i) < pixels(i).length) pixels(i)(sent(i)).U else 0.U)
        if (sent(i) < pixels(i).length && dut.io.in(i).ready.peek().litToBoolean) sent(i) += 1
        if (ready && dut.io.out(i).valid.peek().litToBoolean) results(i) += dut.io.out(i).bits.peek().litValue.toInt
      }
      dut.clock.step(1)
      cycles += 1
      assert(cycles < 1000, s"timeout, got ${results.map(_.length)} bytes")
    }
    results.map(_.toSeq)
  }

  def encrypt(dut: Top, plain: Seq[Int], channels: Int): Seq[Int] =
    encryptAll(dut, Seq(plain), channels, stall = Seq(0)).head

  def hex(bytes: Seq[Int]): String = bytes.map(b => f"$b%02x").mkString

  // ciphertext + tag from the official Ascon implementation (pyascon), for
  // key = nonce = 00 .. 0f and the plaintext bytes 00 01 02 ...
  val references = Seq(
    // one block only
    (4, 4, 1, "e770d289d2a44aee7cd0a48ece5274e3ea721f9a8fc4e556f2745972f5a78411"),
    (4, 4, 3, "e770d289d2a44aee7cd0a48ece5274e381bad7e163dcc4970f7873610debbeb1c7978189d16a6367839460d34f5e754c" +
      "d8ab0dcfcbc8c4a40fb52b77e051f1da"),
    // not a square
    (8, 4, 3, "e770d289d2a44aee7cd0a48ece5274e381bad7e163dcc4970f7873610debbeb1c7978189d16a6367839460d34f5e754c" +
      "96d53cd55b00b93a7a9e98c7e5603c99c56ea022f56c3ef96e9383c9b32636f393c8b22a45c4999e09a77833b5599ade" +
      "2fbaf7a37eb7d019ba6b79dd7f583d39")
  )
  
  // two 4x4 grayscale images and their ciphertext + tag from pyascon, with the nonces 00 .. 0f and that + 1
  val twoImages = Seq(Seq.tabulate(16)(i => i), Seq.tabulate(16)(i => 255 - i))
  val twoReferences = Seq(
    "e770d289d2a44aee7cd0a48ece5274e3ea721f9a8fc4e556f2745972f5a78411",
    "e3e7bc55fbe9e4c44cad03ecb996d27997c261a65f2b9a26d064bfa2ef2d8b2d"
  ).map(_.grouped(2).map(Integer.parseInt(_, 16)).toSeq)

  for (r <- roundsPerCycleConfigs) {
    "Top" should s"generate Verilog (roundsPerCycle = $r)" in {
      // fails if any wire in the design is left without a value
      val verilog = ChiselStage.emitSystemVerilog(new Top())
      assert(verilog.contains("module Top"))
    }

    it should s"encrypt a byte stream like the reference (roundsPerCycle = $r)" in {
      import AsconDataVectors._
      import AsconTagVectors._

      // the same 2 blocks as AsconDataSpec / AsconTagSpec, but as single bytes
      def toBytes(blocks: Seq[BigInt]): Seq[Int] =
        blocks.flatMap(b => (0 until 16).map(i => ((b >> (8 * i)) & 0xff).toInt))
      val expected = toBytes(cipherBlocks) ++ toBytes(Seq(tag))

      test(new Top(width = 8, height = 4)) { dut =>
        val result = encrypt(dut, toBytes(plainBlocks), channels = 1)
        assert(result == expected, s"\n  got      ${hex(result)}\n  expected ${hex(expected)}")
      }
    }


    it should s"encrypt two images at the same time like the reference (roundsPerCycle = $r)" in {
      test(new Top(4, 4, 1, numImages = 2)) { dut =>
        val results = encryptAll(dut, twoImages, 1, stall = Seq(0, 0))
        assert(results == twoReferences, s"\n  got      ${results.map(hex)}\n  expected ${twoReferences.map(hex)}")
      }
    }

    it should s"keep the lanes independent when one output stops (roundsPerCycle = $r)" in {
      test(new Top(4, 4, 1, numImages = 2)) { dut =>
        assert(encryptAll(dut, twoImages, 1, stall = Seq(0, 100)) == twoReferences)
      }
    }

    it should s"generate one lane for each image (roundsPerCycle = $r)" in {
      for (numImages <- Seq(1, 2, 3)) {
        val verilog = ChiselStage.emitSystemVerilog(new Top(8, 4, 1, numImages))
        assert("ImageLane \\w+ \\(".r.findAllIn(verilog).length == numImages)
      }
    }

    it should s"emit a different circuit for grayscale and RGB (roundsPerCycle = $r)" in {
      val gray = ChiselStage.emitSystemVerilog(new Top(8, 4, 1))
      val rgb = ChiselStage.emitSystemVerilog(new Top(8, 4, 3))
      assert(gray != rgb)
      assert(gray.contains("[23:0]") && rgb.contains("[23:0]"))
    }

    it should s"refuse image formats it can not generate (roundsPerCycle = $r)" in {
      // 900 bytes, not a multiple of 16
      intercept[IllegalArgumentException] { ChiselStage.emitSystemVerilog(new Top(30, 30, 1)) }
      intercept[IllegalArgumentException] { ChiselStage.emitSystemVerilog(new Top(64, 64, 2)) }
      intercept[IllegalArgumentException] { ChiselStage.emitSystemVerilog(new Top(0, 64, 1)) }
      intercept[IllegalArgumentException] { ChiselStage.emitSystemVerilog(new Top(64, 64, 1, numImages = 0)) }
    }
  }
}