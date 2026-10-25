package engine

import chisel3._
import chiseltest._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec

class TopSpec extends AnyFlatSpec with ChiselScalatestTester {
  "Top" should "generate Verilog" in {
    // fails if any wire in the design is left without a value
    val verilog = ChiselStage.emitSystemVerilog(new Top())
    assert(verilog.contains("module Top"))
  }

  it should "send back every byte it receives over UART" in {
    // slow clock so that one bit lasts only 10 cycles and the simulation is fast
    val cyclesPerBit = 10
    test(new Top(clockFreq = 1000, baudRate = 100)) { dut =>
      // plays the role of the PC: start bit, 8 data bits (LSB first), stop bit
      def sendByte(b: Int): Unit = {
        val bits = 0 +: (0 until 8).map(i => (b >> i) & 1) :+ 1
        for (bit <- bits) {
          dut.io.rxd.poke((bit == 1).B)
          dut.clock.step(cyclesPerBit)
        }
      }

      // reads one byte from txd, sampling in the middle of each bit
      def receiveByte(): Int = {
        var waited = 0
        while (dut.io.txd.peek().litToBoolean) {
          dut.clock.step(1)
          waited += 1
          assert(waited < 1000, "no start bit on txd")
        }
        dut.clock.step(cyclesPerBit + cyclesPerBit / 2)
        var b = 0
        for (i <- 0 until 8) {
          if (dut.io.txd.peek().litToBoolean) b |= 1 << i
          dut.clock.step(cyclesPerBit)
        }
        dut.io.txd.expect(true.B, "missing stop bit")
        b
      }

      // idle line is high
      dut.io.rxd.poke(true.B)
      dut.clock.step(5)

      // bytes are sent back to back, so the core receives the next byte
      // while the previous one is still being sent (needs the queues)
      val bytes = Seq(0x00, 0x55, 0xa5, 0xff, 0x42, 0x81, 0x7e, 0x01)
      val sender = fork { bytes.foreach(sendByte) }
      val received = bytes.map(_ => receiveByte())
      sender.join()

      assert(received == bytes, s"got ${received.map(b => f"$b%02x")}")
    }
  }
}
