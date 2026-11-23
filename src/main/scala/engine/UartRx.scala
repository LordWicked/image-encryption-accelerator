package engine

import chisel3.IO
import chisel3.Bundle
import chisel3.UInt
import chisel3.RegInit
import chisel3._
import chisel3.util._

class UartRx(clockFreq: Int, baudRate: Int) extends Module {

  val cyclesPerBit = clockFreq / baudRate

  val io = IO(new UartReceiverBundle(8))
  val busy = RegInit(false.B) // If a bit is still being processed or not
  val timer = RegInit(
    0.U(log2Ceil(cyclesPerBit * 2).W)
  ) // How many cycles have you made
  val bitCount = RegInit(0.U(4.W)) // Bits that were processed
  val shifter = RegInit(0.U(8.W))

  io.valid := false.B
  io.data := shifter

  when(!busy) {
    // Initial phase.. Starting timer
    when(io.rx === false.B) {
      busy := true.B
      timer := (cyclesPerBit + (cyclesPerBit / 2) - 1).U
      bitCount := 0.U
    }
  } otherwise {
    when(timer === 0.U) {

      when(bitCount < 8.U) {
        shifter := Cat(io.rx, shifter(7, 1))
        timer := (cyclesPerBit - 1).U
        bitCount := bitCount + 1.U

      }.otherwise {
        io.valid := true.B
        busy := false.B
      }
    }.otherwise {
      timer := timer - 1.U
    }
  }

}

class UartReceiverBundle(width: Int) extends Bundle {
  val rx = Input(Bool())
  val valid = Output(Bool())
  val data = Output(UInt(width.W))
}

class UartReceiverWrapper(clockFreq: Int, baudRate: Int) extends Module {

  val io = IO(new Bundle {
    val rx = Input(Bool())
    val out = DecoupledIO(UInt(8.W))
  })

  val uartRx = Module(new UartRx(clockFreq, baudRate))

  uartRx.io.rx := io.rx
  io.out.valid := uartRx.io.valid
  io.out.bits := uartRx.io.data

}
