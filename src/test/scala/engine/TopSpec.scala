package engine

import chisel3._
import chiseltest._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class TopSpec extends AnyFlatSpec with ChiselScalatestTester {
  import AsconInitVectors._

  // streams the bytes through Packer -> Ascon -> Unpacker, returns ciphertext + tag
  def encrypt(dut: Top, plain: Seq[Int]): Seq[Int] = {
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
      dut.io.in.valid.poke((sent < plain.length).B)
      dut.io.in.bits.poke(if (sent < plain.length) plain(sent).U else 0.U)
      if (sent < plain.length && dut.io.in.ready.peek().litToBoolean) sent += 1
      if (dut.io.out.valid.peek().litToBoolean) result += dut.io.out.bits.peek().litValue.toInt
      dut.clock.step(1)
      cycles += 1
      assert(cycles < 1000, s"timeout, got ${result.length} of ${plain.length + 16} bytes")
    }
    result.toSeq
  }

  def hex(bytes: Seq[Int]): String = bytes.map(b => f"$b%02x").mkString

  "Top" should "generate Verilog" in {
    // fails if any wire in the design is left without a value
    val verilog = ChiselStage.emitSystemVerilog(new Top())
    assert(verilog.contains("module Top"))
  }

  it should "encrypt a byte stream like the reference" in {
    import AsconDataVectors._
    import AsconTagVectors._

    // the same 2 blocks as AsconDataSpec / AsconTagSpec, but as single bytes
    def toBytes(blocks: Seq[BigInt]): Seq[Int] =
      blocks.flatMap(b => (0 until 16).map(i => ((b >> (8 * i)) & 0xff).toInt))
    val expected = toBytes(cipherBlocks) ++ toBytes(Seq(tag))

    test(new Top(width = 8, height = 4)) { dut =>
      val result = encrypt(dut, toBytes(plainBlocks))
      assert(result == expected, s"\n  got      ${hex(result)}\n  expected ${hex(expected)}")
    }
  }

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

  for ((width, height, channels, reference) <- references) {
    it should s"encrypt a ${width}x$height image with $channels channel(s) like the reference" in {
      val plain = Seq.tabulate(width * height * channels)(_ % 256)
      val expected = reference.grouped(2).map(Integer.parseInt(_, 16)).toSeq

      test(new Top(width, height, channels)) { dut =>
        val result = encrypt(dut, plain)
        assert(result == expected, s"\n  got      ${hex(result)}\n  expected ${hex(expected)}")
      }
    }
  }

  it should "refuse image formats it can not generate" in {
    // 900 bytes, not a multiple of 16
    intercept[IllegalArgumentException] { ChiselStage.emitSystemVerilog(new Top(30, 30, 1)) }
    intercept[IllegalArgumentException] { ChiselStage.emitSystemVerilog(new Top(64, 64, 2)) }
    intercept[IllegalArgumentException] { ChiselStage.emitSystemVerilog(new Top(0, 64, 1)) }
  }
}
