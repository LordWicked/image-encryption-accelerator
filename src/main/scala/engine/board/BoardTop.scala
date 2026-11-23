package engine

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import engine.uart._
import engine.board._
// Nexys A7 board: UART in -> 3 bytes per RGB pixel -> Top -> UART out
// key and nonce fixed to 00 .. 0f like in Main, Top restarts after each tag.
// echo = true skips Top and sends every byte back, to test the UART alone.
// The PC always sends 3 bytes (R, G, B) per pixel, also for grayscale
class BoardTop(
    width: Int,
    height: Int,
    channels: Int,
    baudRate: Int,
    echo: Boolean
) extends Module {
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

    // a byte arrives every 10 bit times, the queue covers the moments Top is busy
    val rxQueue = Module(new Queue(UInt(8.W), 256))
    rxQueue.io.enq.valid := rx.io.valid
    rxQueue.io.enq.bits := rx.io.data

    // the board sends 1% faster than it receives. With exactly the same rate the queue slowly
    // filled up and bytes got lost. The PC still reads every bit because it samples mid bit
    val tx = Module(new BoardUartTx(clockFreq / baudRate * 99 / 100))
    io.txd := tx.io.txd

    // leds show how many bytes arrived
    val received = RegInit(0.U(16.W))
    when(rx.io.valid) { received := received + 1.U }
    io.led := received

    if (echo) {
      tx.io.in <> rxQueue.io.deq
    } else {
      // one lane, the UART brings one image at a time
      val top = Module(new Top(width, height, channels))
      val pixelIn = top.io.in(0)
      val bytesOut = top.io.out(0)
      top.io.key := "h0f0e0d0c0b0a09080706050403020100".U
      top.io.nonce(0) := "h0f0e0d0c0b0a09080706050403020100".U

      // R, G, B bytes -> one 24 bit pixel
      val bytes = Reg(Vec(2, UInt(8.W)))
      val index = RegInit(0.U(2.W))
      pixelIn.valid := rxQueue.io.deq.valid && index === 2.U
      pixelIn.bits := Cat(bytes(0), bytes(1), rxQueue.io.deq.bits)
      rxQueue.io.deq.ready := index =/= 2.U || pixelIn.ready
      when(rxQueue.io.deq.fire) {
        when(index === 2.U) {
          index := 0.U
        }.otherwise {
          bytes(index(0)) := rxQueue.io.deq.bits // index is 0 or 1 here
          index := index + 1.U
        }
      }

      // per image the PC gets the ciphertext and then the 16 byte tag
      tx.io.in <> bytesOut

      // start once after reset and again after the last tag byte of every image
      val outBytes = width * height * channels + 16
      val sent = RegInit(0.U(32.W))
      val start = RegInit(true.B)
      start := false.B
      top.io.start := start
      when(bytesOut.fire) {
        when(sent === (outBytes - 1).U) {
          sent := 0.U
          start := true.B
        }.otherwise {
          sent := sent + 1.U
        }
      }
    }
  }
}

// sbt "runMain engine.BoardMain 256 256 rgb 2000000"       -> generated/board/BoardTop.sv
// sbt "runMain engine.BoardMain 256 256 rgb 2000000 echo"  UART test
object BoardMain extends App {
  require(
    args.length >= 4,
    "usage: BoardMain <width> <height> <rgb|gray> <baud> [echo]"
  )
  val width = args(0).toInt
  val height = args(1).toInt
  require(
    args(2) == "rgb" || args(2) == "gray",
    s"mode must be rgb or gray, got ${args(2)}"
  )
  val channels = if (args(2) == "rgb") 3 else 1
  val baud = args(3).toInt
  val echo = args.length > 4 && args(4) == "echo"
  ChiselStage.emitSystemVerilogFile(
    new BoardTop(width, height, channels, baud, echo),
    args = Array("--target-dir", "generated/board"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
