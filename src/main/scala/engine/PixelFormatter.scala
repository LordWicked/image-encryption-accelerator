package engine

import chisel3._
import chisel3.util._

object PixelFormatter {
  // luminance weights in 1/256 steps (0.299, 0.587, 0.114), they add up to 256
  // so a pixel with R = G = B keeps its exact value
  val redWeight = 77
  val greenWeight = 150
  val blueWeight = 29

  // the same calculation in software, for the PC side and the tests
  def gray(rgb: Int): Int = {
    val (r, g, b) = ((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff)
    (redWeight * r + greenWeight * g + blueWeight * b) >> 8
  }
}

// turns one RGB pixel (R in bits 23..16, B in bits 7..0) into the bytes to encrypt
// the generator builds a different circuit for each format:
//   channels = 3: the bytes R, G and B, one per transfer
//   channels = 1: one grayscale byte
class PixelFormatter(channels: Int) extends Module {
  require(channels == 1 || channels == 3, s"channels must be 1 (grayscale) or 3 (RGB), got $channels")

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(24.W)))
    val out = Decoupled(UInt(8.W))
  })

  val red = io.in.bits(23, 16)
  val green = io.in.bits(15, 8)
  val blue = io.in.bits(7, 0)

  io.out.valid := io.in.valid

  if (channels == 1) {
    import PixelFormatter._
    val sum = red * redWeight.U + green * greenWeight.U + blue * blueWeight.U
    io.out.bits := sum(15, 8)
    io.in.ready := io.out.ready
  } else {
    // which byte of the pixel goes out now, the pixel stays at the input until the last one
    val count = RegInit(0.U(2.W))
    io.out.bits := VecInit(red, green, blue)(count)
    io.in.ready := io.out.ready && count === 2.U

    when(io.out.fire) {
      count := Mux(count === 2.U, 0.U, count + 1.U)
    }
  }
}
