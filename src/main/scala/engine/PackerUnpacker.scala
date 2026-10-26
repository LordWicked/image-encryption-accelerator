package engine

import chisel3._
import chisel3.util._

// packer accumulates nBytes bytes to output one (8 * nBytes) bit word
// e.g. nBytes = 8 gives the 64 bit words the Ascon core expects
class Packer(nBytes: Int = 16) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(8.W)))
    val out = Decoupled(UInt((8 * nBytes).W))
  })

  // vector to hold nBytes bytes of data
  val buffer = RegInit(VecInit(Seq.fill(nBytes)(0.U(8.W))))

  // counter from 0 to nBytes - 1 to track received bytes
  val count = RegInit(0.U(log2Ceil(nBytes).W))

  // flag that goes high when the bucket is full
  val full = RegInit(false.B)

  // we are ready to receive if the bucket is not full
  io.in.ready := !full

  // we are sending valid data when the bucket is full
  io.out.valid := full

  // squash the vector into a single wide word (byte 0 in the lowest bits)
  io.out.bits := buffer.asUInt

  // logic to fill the bucket
  when(io.in.valid && io.in.ready) {
    buffer(count) := io.in.bits
    count := count + 1.U

    // if we just wrote the last index we are full
    when(count === (nBytes - 1).U) {
      full := true.B
    }
  }

  // logic to empty the bucket when receiver accepts it
  when(io.out.valid && io.out.ready) {
    count := 0.U
    full := false.B
  }
}

// unpacker breaks one (8 * nBytes) bit word into nBytes individual bytes
class Unpacker(nBytes: Int = 16) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt((8 * nBytes).W)))
    val out = Decoupled(UInt(8.W))
  })

  val buffer = RegInit(VecInit(Seq.fill(nBytes)(0.U(8.W))))
  val count = RegInit(0.U(log2Ceil(nBytes).W))

  // flag that goes high when we are busy outputting bytes
  val active = RegInit(false.B)

  // we can only receive a new word if we are not busy
  io.in.ready := !active
  io.out.valid := active

  // output one byte at a time based on the counter
  io.out.bits := buffer(count)

  // logic to receive the wide word
  when(io.in.valid && io.in.ready) {
    // cast the word directly into our byte vector
    buffer := io.in.bits.asTypeOf(Vec(nBytes, UInt(8.W)))
    active := true.B
    count := 0.U
  }

  // logic to send bytes one by one
  when(io.out.valid && io.out.ready) {
    count := count + 1.U

    // if we just sent the last byte we are done
    when(count === (nBytes - 1).U) {
      active := false.B
    }
  }
}
