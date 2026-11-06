package engine

import chisel3._
import chisel3.util._

// stream interface (issue #7)
class StreamIO extends Bundle {
  // input pixel stream
  val in = Flipped(
    Decoupled(UInt(8.W))
  ) // flipped so it inverts the direction (it is an input)

  // output pixel stream
  val out = Decoupled(UInt(8.W))
}

// any core with the byte stream interface can be plugged into Top
trait HasStreamIO extends Module {
  val io = IO(new StreamIO)
}

// simple passthrough core for the walking skeleton (issue #8)
class PassthroughCore extends HasStreamIO {
  // just wire everything directly
  // this connects bits valid and ready automatically
  io.out <> io.in
}
