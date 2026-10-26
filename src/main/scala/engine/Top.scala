package engine

import chisel3._
import chisel3.util._

// top level for the FPGA: PC -> UART RX -> core -> UART TX -> PC (issue #26)
// the core is a parameter, so the passthrough can later be swapped for the Ascon core
class Top(
    gen: () => HasStreamIO = () => new PassthroughCore,
    clockFreq: Int = 100000000, // 100 MHz oscillator on the Nexys A7
    baudRate: Int = 115200,
    fifoDepth: Int = 16
) extends Module {
  val io = IO(new Bundle {
    val rxd = Input(Bool()) // serial line coming from the PC
    val txd = Output(Bool()) // serial line going to the PC
  })

  // rxd comes from outside the FPGA and is not synchronized with our clock,
  // two registers in a row avoid metastability (idle level of the line is 1)
  val rxdSync = RegNext(RegNext(io.rxd, true.B), true.B)

  val uartRx = Module(new UartReceiverWrapper(clockFreq, baudRate))
  uartRx.io.rx := rxdSync

  val core = Module(gen())
  val uartTx = Module(new UartTx(clockFreq / baudRate))

  // the receiver does not wait for ready, so the queues hold the bytes
  // while the core or the transmitter are busy instead of losing them
  core.io.in <> Queue(uartRx.io.out, fifoDepth)
  uartTx.io.in <> Queue(core.io.out, fifoDepth)

  // the "byte sent" pulse of the transmitter is not needed here
  uartTx.io.out.ready := true.B

  io.txd := uartTx.io.txd
}
