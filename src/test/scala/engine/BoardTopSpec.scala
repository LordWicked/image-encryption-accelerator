package engine

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer
import engine.board.BoardTop

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
      val bits = Seq(0) ++ (0 until 8).map(i => (b >> i) & 1) ++ Seq(
        1,
        1
      ) // start, data, stop, 1 idle bit
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
        if (!txd) {
          rxBitsLeft = 8; countdown = txCyclesPerBit + txCyclesPerBit / 2;
          byte = 0
        }
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
      assert(
        cycle < wave.length + 20 * cyclesPerBit * expectedLength + 10000,
        s"timeout, got ${received.length} bytes"
      )
    }
    received.toSeq
  }

  "BoardTop" should "send every byte back" in {
    test(new BoardTop(baud)) { dut =>
      val data = Seq(0x00, 0x55, 0xaa, 0xff, 0x12, 0x34)
      assert(run(dut, data, data.length) == data)
    }
  }

  it should "count the received bytes on the LEDs" in {
    test(new BoardTop(baud)) { dut =>
      run(dut, Seq(1, 2, 3, 4, 5), 5)
      assert(dut.io.led.peek().litValue == 5)
    }
  }
}
