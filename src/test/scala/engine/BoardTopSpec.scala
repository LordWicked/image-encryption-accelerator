package engine

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class BoardTopSpec extends AnyFlatSpec with ChiselScalatestTester {
  // fast uart so the simulation stays short. 10 cycles per bit into the board, it sends 1% faster
  // which rounds down to 9 cycles here, so the decoder reads with 9
  val cyclesPerBit = 10
  val txCyclesPerBit = cyclesPerBit * 99 / 100
  val baud = 100000000 / cyclesPerBit

  // drives the rx pin bit by bit and decodes the tx pin like a PC would
  def run(dut: BoardTop, data: Seq[Int], expectedLength: Int): Seq[Int] = {
    dut.clock.setTimeout(0)
    // the board keeps itself in reset for its first 256 cycles
    dut.io.rxd.poke(true.B)
    dut.clock.step(300)
    val wave = data.flatMap { b =>
      val bits = Seq(0) ++ (0 until 8).map(i => (b >> i) & 1) ++ Seq(1, 1) // start, data, stop, 1 idle bit
      bits.flatMap(Seq.fill(cyclesPerBit)(_))
    }
    val received = ArrayBuffer[Int]()
    var cycle = 0
    var rxBitsLeft = -1 // -1 = idle
    var countdown = 0
    var byte = 0
    while (received.length < expectedLength) {
      dut.io.rxd.poke((if (cycle < wave.length) wave(cycle) == 1 else true).B)
      val txd = dut.io.txd.peek().litToBoolean
      if (rxBitsLeft == -1) {
        if (!txd) { rxBitsLeft = 8; countdown = txCyclesPerBit + txCyclesPerBit / 2; byte = 0 }
      } else {
        countdown -= 1
        if (countdown == 0) {
          if (rxBitsLeft > 0) {
            byte |= (if (txd) 1 else 0) << (8 - rxBitsLeft)
            rxBitsLeft -= 1
            countdown = txCyclesPerBit
          } else {
            received += byte
            rxBitsLeft = -1
          }
        }
      }
      dut.clock.step(1)
      cycle += 1
      assert(cycle < wave.length + 20 * cyclesPerBit * expectedLength + 10000, s"timeout, got ${received.length} bytes")
    }
    received.toSeq
  }

  // ciphertext + tag from pyascon for the bytes 00 01 .. 2f (a 4x4 RGB image), key 00 .. 0f.
  // first image with nonce 00 .. 0f (same as the 4x4 RGB case in TopSpec), second with nonce + 1
  val nonces = Seq("000102030405060708090a0b0c0d0e0f", "010102030405060708090a0b0c0d0e0f")
  val references = Seq(
    "e770d289d2a44aee7cd0a48ece5274e381bad7e163dcc4970f7873610debbeb1" +
      "c7978189d16a6367839460d34f5e754cd8ab0dcfcbc8c4a40fb52b77e051f1da",
    "1c1843aa04161b3bb352fc1346692d8697beefb628bd448b1f04b870dc0ea894" +
      "af94f5ac767febf8552cde3c97ce0db812d43a87d34325bf2408f48981ebb962"
  )
  def bytes(hex: String): Seq[Int] = hex.grouped(2).map(Integer.parseInt(_, 16)).toSeq
  val image = Seq.tabulate(48)(i => i)

  "BoardTop" should "send every byte back in echo mode" in {
    test(new BoardTop(4, 4, 3, baud, echo = true)) { dut =>
      val data = Seq(0x00, 0x55, 0xaa, 0xff, 0x12, 0x34)
      assert(run(dut, data, data.length) == data)
    }
  }

  it should "send nonce + ciphertext + tag like pyascon, with a new nonce for the second image" in {
    test(new BoardTop(4, 4, 3, baud, echo = false)) { dut =>
      assert(dut.io.led.peek().litValue == 0, "LEDs off before the first image")
      val expected = nonces.zip(references).map { case (n, r) => bytes(n) ++ bytes(r) }
      val result = run(dut, image ++ image, expected.map(_.length).sum)
      assert(result.take(expected(0).length) == expected(0), "first image")
      assert(result.drop(expected(0).length) == expected(1), "second image")
      assert(dut.io.led.peek().litValue == 0xffff, "all 16 LEDs on when the image is done")
    }
  }

  "BcdCounter" should "count in decimal" in {
    test(new BcdCounter(4)) { dut =>
      dut.clock.setTimeout(0)
      dut.io.inc.poke(true.B)
      dut.clock.step(1234)
      assert(dut.io.digits.map(_.peek().litValue.toInt) == Seq(4, 3, 2, 1))
    }
  }
}
