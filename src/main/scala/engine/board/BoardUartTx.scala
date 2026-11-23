package engine.board

import chisel3._
import chisel3.util._

// uart transmitter with a ready/valid input: start bit, 8 data bits (LSB first), stop bit.
// Takes the bit time in clock cycles, so BoardTop can send a bit faster than it receives
class BoardUartTx(cyclesPerBit: Int) extends Module {
  require(
    cyclesPerBit > 1,
    s"cyclesPerBit must be at least 2, got $cyclesPerBit"
  )
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(8.W)))
    val txd = Output(Bool())
  })
  val shift = RegInit("h3ff".U(10.W)) // all ones = line idle
  val bitsLeft = RegInit(0.U(4.W))
  val timer = RegInit(0.U(log2Ceil(cyclesPerBit).W))

  io.in.ready := bitsLeft === 0.U
  io.txd := shift(0)

  when(io.in.fire) {
    shift := Cat(1.U(1.W), io.in.bits, 0.U(1.W)) // stop bit, data, start bit
    bitsLeft := 10.U
    timer := (cyclesPerBit - 1).U
  }.elsewhen(bitsLeft =/= 0.U) {
    when(timer === 0.U) {
      shift := Cat(1.U(1.W), shift(9, 1))
      bitsLeft := bitsLeft - 1.U
      timer := (cyclesPerBit - 1).U
    }.otherwise {
      timer := timer - 1.U
    }
  }
}
