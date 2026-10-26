package engine

import chisel3._
import chisel3.util._

// packer accumulates 16 bytes to output a 128 bit block
class Packer extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(8.W)))
    val out = Decoupled(UInt(128.W))
  })

  // vector to hold 16 bytes of data
  val buffer = RegInit(VecInit(Seq.fill(16)(0.U(8.W))))

  // counter from 0 to 15 to track received bytes
  val count = RegInit(0.U(4.W))

  // flag that goes high when the bucket is full
  val full = RegInit(false.B)

  // we are ready to receive if the bucket is not full
  io.in.ready := !full

  // we are sending valid data when the bucket is full
  io.out.valid := full

  // squash the vector into a single 128 bit wire
  io.out.bits := buffer.asUInt

  // logic to fill the bucket
  when(io.in.valid && io.in.ready) {
    buffer(count) := io.in.bits
    count := count + 1.U

    // if we just wrote the 15th index we are full
    when(count === 15.U) {
      full := true.B
    }
  }

  // logic to empty the bucket when receiver accepts it
  when(io.out.valid && io.out.ready) {
    count := 0.U
    full := false.B
  }
}

// unpacker breaks a 128 bit block into 16 individual bytes
class Unpacker extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(128.W)))
    val out = Decoupled(UInt(8.W))
  })

  val buffer = RegInit(VecInit(Seq.fill(16)(0.U(8.W))))
  val count = RegInit(0.U(4.W))

  // flag that goes high when we are busy outputting bytes
  val active = RegInit(false.B)

  // we can only receive a new block if we are not busy
  io.in.ready := !active
  io.out.valid := active

  // output one byte at a time based on the counter
  io.out.bits := buffer(count)

  // logic to receive the big block
  when(io.in.valid && io.in.ready) {
    // cast the 128 bit block directly into our 16 byte vector
    buffer := io.in.bits.asTypeOf(Vec(16, UInt(8.W)))
    active := true.B
    count := 0.U
  }

  // logic to send bytes one by one
  when(io.out.valid && io.out.ready) {
    count := count + 1.U

    // if we just sent the last byte we are done
    when(count === 15.U) {
      active := false.B
    }
  }
}
