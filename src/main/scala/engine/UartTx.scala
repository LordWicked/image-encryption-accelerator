package engine

import chisel3._
import chisel3.util._

class UartTx(cyclesPerBit: Int) extends Module {

  val io = IO(new UartTxBundle)

  // This is just to know how many transfers have we done
  val bitCount = RegInit(0.U(4.W))

  val busy = RegInit(false.B)

  val timer = RegInit(0.U(log2Ceil(cyclesPerBit * 2).W))

  val cur_data = RegInit(0.U(10.W))

  io.txd := true.B
  io.out.valid := false.B
  io.out.bits := 0.U

  // A new byte is only accepted when the previous one was fully sent
  io.in.ready := !busy

  when(!busy) {

    when(io.in.valid) {

      busy := true.B
      timer := (cyclesPerBit - 1).U
      bitCount := 0.U
      cur_data := Cat(1.U(1.W), io.in.bits, 0.U(1.W))
    }
  }.otherwise {
    io.txd := cur_data(0)
    when(timer === 0.U) {
      timer := (cyclesPerBit - 1).U

      when(bitCount < 9.U) {
        cur_data := cur_data >> 1
        bitCount := bitCount + 1.U
      }.otherwise {
        busy := false.B
        // A last end bit to end the connection
        io.out.valid := true.B
      }
    }.otherwise {
      timer := timer - 1.U
    }
  }

}

class UartTxBundle() extends StreamIO {
  val txd = Output(Bool())
}
