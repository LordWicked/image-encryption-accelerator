package engine

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class PassthroughCoreSpec extends AnyFlatSpec with ChiselScalatestTester {
  "PassthroughCore" should "pass data and respect backpressure" in {
    test(new PassthroughCore) { dut =>

      // initial state no one sending and no one ready
      dut.io.in.valid.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.clock.step(1)

      // sender tries to push a pixel but receiver is not ready yet
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke(42.U)
      dut.io.out.ready.poke(false.B)

      // core should hold back the sender by keeping ready low
      dut.io.in.ready.expect(false.B)
      dut.clock.step(1)

      // now the receiver is finally ready for data
      dut.io.out.ready.poke(true.B)

      // the core tells the sender it can proceed
      dut.io.in.ready.expect(true.B)

      // check if the pixel actually reached the other side
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.expect(42.U)

      dut.clock.step(1)
    }
  }
}