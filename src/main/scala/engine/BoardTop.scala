package engine

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Nexys A7 board, first step: UART loopback. Every byte that arrives is sent back,
// to test the link to the PC before the encryption is connected
class BoardTop(baudRate: Int) extends Module {
  val io = IO(new Bundle {
    val rxd = Input(Bool())
    val txd = Output(Bool())
    val led = Output(UInt(16.W))
    val seg = Output(UInt(7.W)) // segments a..g, active low
    val an = Output(UInt(8.W)) // digit enables, active low
  })
  val clockFreq = 100000000

  // after programming, the registers do not always start at their reset value (vivado keeps it
  // only for some of them). The tx line started low and the PC lost the first byte, so the board
  // stays in reset for the first 256 cycles. powerOn has no reset, it starts at 0
  val powerOn = Reg(UInt(8.W))
  when(powerOn =/= 255.U) { powerOn := powerOn + 1.U }

  withReset(reset.asBool || powerOn =/= 255.U) {
    io.seg := "h7f".U // display off
    io.an := "hff".U

    // rx comes from outside the clock domain, so two registers before using it
    val rx = Module(new UartRx(clockFreq, baudRate))
    rx.io.rx := RegNext(RegNext(io.rxd, true.B), true.B)

    // a byte arrives every 10 bit times, the queue covers the moments the transmitter is busy
    val rxQueue = Module(new Queue(UInt(8.W), 256))
    rxQueue.io.enq.valid := rx.io.valid
    rxQueue.io.enq.bits := rx.io.data

    // the board sends 1% faster than it receives. With exactly the same rate the queue slowly
    // filled up and bytes got lost. The PC still reads every bit because it samples mid bit
    val tx = Module(new BoardUartTx(clockFreq / baudRate * 99 / 100))
    io.txd := tx.io.txd
    tx.io.in <> rxQueue.io.deq

    // leds show how many bytes arrived
    val received = RegInit(0.U(16.W))
    when(rx.io.valid) { received := received + 1.U }
    io.led := received
  }
}

// sbt "runMain engine.BoardMain 2000000"  -> generated/board/BoardTop.sv, echo at 2 Mbaud
object BoardMain extends App {
  val baud = if (args.nonEmpty) args(0).toInt else 2000000
  ChiselStage.emitSystemVerilogFile(
    new BoardTop(baud),
    args = Array("--target-dir", "generated/board"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
