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

  // streams the pixels through the whole Top, returns ciphertext + tag
  def encrypt(dut: Top, plain: Seq[Int], channels: Int): Seq[Int] = {
    val pixels = toPixels(plain, channels)
    dut.io.key.poke(toCoreInput(keyBytes).U(128.W))
    dut.io.nonce.poke(toCoreInput(nonceBytes).U(128.W))
    dut.io.start.poke(true.B)
    dut.clock.step(1)
    dut.io.start.poke(false.B)
    dut.io.out.ready.poke(true.B)

    val result = ArrayBuffer[Int]()
    var sent = 0
    var cycles = 0
    while (result.length < plain.length + 16) {
      dut.io.in.valid.poke((sent < pixels.length).B)
      dut.io.in.bits.poke(if (sent < pixels.length) pixels(sent).U else 0.U)
      if (sent < pixels.length && dut.io.in.ready.peek().litToBoolean) sent += 1
      if (dut.io.out.valid.peek().litToBoolean) result += dut.io.out.bits.peek().litValue.toInt
      dut.clock.step(1)
      cycles += 1
      assert(cycles < 1000, s"timeout, got ${result.length} of ${plain.length + 16} bytes")
    }
    result.toSeq
  }

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


    for ((width, height, channels, reference) <- references) {
      it should s"encrypt a ${width}x$height image with $channels channel(s) like the reference (roundsPerCycle = $r)" in {
        val plain = Seq.tabulate(width * height * channels)(_ % 256)
        val expected = reference.grouped(2).map(Integer.parseInt(_, 16)).toSeq

        test(new Top(width, height, channels)) { dut =>
          val result = encrypt(dut, plain, channels)
          assert(result == expected, s"\n  got      ${hex(result)}\n  expected ${hex(expected)}")
        }
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
    }
  }
}
