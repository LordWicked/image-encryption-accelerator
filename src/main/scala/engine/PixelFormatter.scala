package engine

import chisel3._
import chisel3.util._

/*
  This object is used whenever its necessary to transform the 24 bits RGB into
  Grayscale depending on the user preference.
 */
object PixelFormatter {
  val redWeight = 77
  val greenWeight = 150
  val blueWeight = 29

  def gray(rgb: Int): Int = {
    val (r, g, b) = ((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff)
    (redWeight * r + greenWeight * g + blueWeight * b) >> 8
  }
}

/*
  Pre: Channels must be 1 or 3
  Input: 24 bits of data
  This class is responsible for managing the channels generator
  if the user prefers grayscale it transforms the rgb into grayscale and if
  not it needs to send the 24 bits separately (8 bits in each cock).
 */
class PixelFormatter(channels: Int) extends Module {

  require(
    channels == 1 || channels == 3,
    s"channels must be 1 (grayscale) or 3 (RGB), got $channels"
  )

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(24.W)))
    val out = Decoupled(UInt(8.W))
  })

  val red = io.in.bits(23, 16)
  val green = io.in.bits(15, 8)
  val blue = io.in.bits(7, 0)

  io.out.valid := io.in.valid

  if (channels == 1) {
    // Technique to use the variables of Pixel formatter object inside the class.
    import PixelFormatter._

    val sum = red * redWeight.U + green * greenWeight.U + blue * blueWeight.U
    io.out.bits := sum(15, 8)
    io.in.ready := io.out.ready
  } else {

    val count = RegInit(0.U(2.W))
    io.out.bits := VecInit(red, green, blue)(count)
    io.in.ready := io.out.ready && count === 2.U

    when(io.out.fire) {
      count := Mux(count === 2.U, 0.U, count + 1.U)
    }
  }

}
