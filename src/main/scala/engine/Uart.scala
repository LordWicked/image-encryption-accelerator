package engine

import chisel3._
import chisel3.util._

class Uart(gen: () => PassthroughCore) extends Module {
  val io = IO(new Bundle {
    val rxd = Input(Bool())
    val txd =
      Output(Bool())
  })

  val clockFreq =
    100000000 // 100 MHZ established by the company that made the FPGA
  val baudRate = 115200 // standard number

  val uartRx = Module(new UartReceiverWrapper(clockFreq, baudRate))
  uartRx.io.rx := io.rxd

  val rxStream = Wire(new StreamIO)
  rxStream.in.valid := false.B
  rxStream.in.bits := 0.U(8.W)

  rxStream.in.valid := uartRx.io.out.valid
  rxStream.in.bits := uartRx.io.out.bits

  val runner = Module(gen())

  runner.io.in <> rxStream.in

  val uartTx = Module(
    new UartTx(clockFreq / baudRate)
  ) // Assume que tens o UartTx equivalente
  uartTx.io.in.valid := runner.io.out.valid
  uartTx.io.in.bits := runner.io.out.bits
  io.txd := uartTx.io.txd

  // Sinaliza ao core.io.out que já consumimos o dado (o UartTx está sempre pronto para aceitar)
  runner.io.out.ready := uartTx.io.out.valid
}
