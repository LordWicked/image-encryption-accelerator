package engine

import chisel3._
import chisel3.util._

class PassThroughEngine extends Module {
  val io = IO(new Bundle {
    // Flipped reverses the interface: valid and bits become inputs, ready becomes an output
    val in = Flipped(Decoupled(UInt(8.W)))

    // Standard Decoupled: valid and bits are outputs, ready is an input
    val out = Decoupled(UInt(8.W))
  })

  // Pass-Through logic: connect input wires directly to output wires.
  // Later, the encryption core (ChaCha20/Ascon) will be instantiated here in the middle.
  io.out.valid := io.in.valid
  io.out.bits  := io.in.bits

  // The ready signal travels backwards (from receiver to sender)
  io.in.ready  := io.out.ready
}