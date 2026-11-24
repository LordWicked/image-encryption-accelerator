package engine

import chisel3._
import chisel3.util._

// decimal counter, one 4-bit digit per decimal place, digits(0) = units
class BcdCounter(n: Int) extends Module {
  val io = IO(new Bundle {
    val inc = Input(Bool())
    val digits = Output(Vec(n, UInt(4.W)))
  })
  val digits = RegInit(VecInit(Seq.fill(n)(0.U(4.W))))
  io.digits := digits
  when(io.inc) {
    // a digit goes up when all digits below it are 9 (they wrap to 0)
    for (i <- 0 until n) {
      val carry = (0 until i).map(j => digits(j) === 9.U).foldLeft(true.B)(_ && _)
      when(carry) { digits(i) := Mux(digits(i) === 9.U, 0.U, digits(i) + 1.U) }
    }
  }
}

// drives the 8 digits of the display one at a time, ~1.3 ms each, fast enough to look like they
// are all on. digits(0) is the rightmost one, leading zeros of each group of 4 stay dark
class SevenSegment extends Module {
  val io = IO(new Bundle {
    val digits = Input(Vec(8, UInt(4.W)))
    val seg = Output(UInt(7.W)) // segments a..g, active low
    val an = Output(UInt(8.W)) // digit enables, active low
  })
  val refresh = RegInit(0.U(20.W))
  refresh := refresh + 1.U
  val select = refresh(19, 17)

  // segment patterns for 0..9, bit 0 = segment a, 1 = on
  val patterns = VecInit(Seq(0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f).map(_.U(7.W)))
  val blank = VecInit((0 until 8).map { i =>
    val top = (i / 4) * 4 + 3
    if (i % 4 == 0) false.B else (i to top).map(j => io.digits(j) === 0.U).reduce(_ && _)
  })
  val digit = io.digits(select)
  io.seg := ~Mux(blank(select), 0.U(7.W), patterns(digit))
  io.an := ~(1.U(8.W) << select)(7, 0)
}
