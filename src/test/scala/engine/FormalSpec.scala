package engine

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._
import scala.util.Try

/*
  Formal verification (lab 8). The modules stay as they are, every wrapper below
  only adds the properties that must always be true around one of them.
  z3 tries every input up to a number of cycles and gives a counterexample if one fails.
 */

// grayscale: one pixel in, one byte out in the same cycle
class GrayFormal extends Module {
  val dut = Module(new PixelFormatter(channels = 1))
  val io = IO(chiselTypeOf(dut.io))
  io <> dut.io

  val red = io.in.bits(23, 16)
  val green = io.in.bits(15, 8)
  val blue = io.in.bits(7, 0)

  // same formula with 32 bits, so it can not overflow
  val exact = (red * 77.U(24.W) + green * 150.U(24.W) + blue * 29.U(24.W)) >> 8
  assert(io.out.bits === exact, "wrong gray value")

  // a pixel that is already gray does not change
  when(red === green && green === blue) { assert(io.out.bits === red) }

  // no pixel is lost or invented
  assert(io.out.fire === io.in.fire)
}

// RGB: one pixel in, three bytes out
class RgbFormal extends Module {
  val dut = Module(new PixelFormatter(channels = 3))
  val io = IO(chiselTypeOf(dut.io))
  io <> dut.io

  // ready/valid rule: the sender keeps the pixel until it is accepted
  when(past(io.in.valid && !io.in.ready)) {
    assume(io.in.valid && stable(io.in.bits))
  }

  // bytes of this pixel that already left
  val sent = RegInit(0.U(2.W))
  when(io.out.fire) { sent := Mux(sent === 2.U, 0.U, sent + 1.U) }

  when(io.out.valid) {
    when(sent === 0.U) { assert(io.out.bits === io.in.bits(23, 16), "red first") }
    when(sent === 1.U) { assert(io.out.bits === io.in.bits(15, 8), "then green") }
    when(sent === 2.U) { assert(io.out.bits === io.in.bits(7, 0), "then blue") }
  }

  // the pixel is accepted exactly with its third byte
  assert(io.in.fire === (io.out.fire && sent === 2.U))

  // while the receiver is not ready the byte stays there
  when(past(io.out.valid && !io.out.ready)) {
    assert(io.out.valid && stable(io.out.bits))
  }
}

// Packer: nBytes bytes in, one word out. Byte number k of the word is the byte number k that
// went in. k can be any number, so this checks all the bytes
class PackerFormal(nBytes: Int) extends Module {
  val dut = Module(new Packer(nBytes))
  val io = IO(chiselTypeOf(dut.io))
  val k = IO(Input(UInt(log2Ceil(nBytes).W)))
  io <> dut.io

  assume(k === RegNext(k, k)) // k never changes

  val count = RegInit(0.U(log2Ceil(nBytes + 1).W))
  val byteK = RegInit(0.U(8.W))
  when(io.in.fire) {
    count := count + 1.U
    when(count === k) { byteK := io.in.bits }
  }
  when(io.out.fire) { count := 0.U }

  assert(io.out.valid === (count === nBytes.U), "valid only with a full word")
  assert(io.in.ready === (count =/= nBytes.U), "no new byte while the word is full")
  when(io.out.valid) {
    assert(io.out.bits.asTypeOf(Vec(nBytes, UInt(8.W)))(k) === byteK, "wrong byte")
  }
}

// Packer -> Unpacker, as a queue: the byte number k that goes in is the byte number k that
// comes out, for any k
class RoundTripFormal(nBytes: Int) extends Module {
  val packer = Module(new Packer(nBytes))
  val unpacker = Module(new Unpacker(nBytes))
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(8.W)))
    val out = Decoupled(UInt(8.W))
    val k = Input(UInt(4.W))
  })
  packer.io.in <> io.in
  unpacker.io.in <> packer.io.out
  io.out <> unpacker.io.out

  assume(io.k === RegNext(io.k, io.k)) // k never changes

  val in = RegInit(0.U(4.W)) // bytes that went in
  val out = RegInit(0.U(4.W)) // bytes that came out
  val byteK = RegInit(0.U(8.W))
  when(io.in.fire) {
    in := in + 1.U
    when(in === io.k) { byteK := io.in.bits }
  }
  when(io.out.fire) { out := out + 1.U }

  when(io.out.valid) { assert(out < in, "byte invented") }
  assert(in - out <= (2 * nBytes).U, "byte lost")
  when(io.out.valid && out === io.k) { assert(io.out.bits === byteK, "wrong byte or wrong order") }
}

class FormalSpec extends AnyFlatSpec with ChiselScalatestTester with Formal {

  // without z3 these tests are skipped
  def prove(dut: => Module, cycles: Int): Unit = {
    assume(Try("z3 --version".!!).isSuccess, "z3 is not installed")
    verify(dut, Seq(BoundedCheck(cycles)))
  }

  "PixelFormatter (formal)" should "give the exact gray value for all 2^24 pixels" in {
    prove(new GrayFormal, 2)
  }

  it should "send R, G and B once and in order, with any backpressure" in {
    prove(new RgbFormal, 20)
  }

  "Packer (formal)" should "only output full words with the bytes in order" in {
    prove(new PackerFormal(4), 11)
  }

  "Packer and Unpacker (formal)" should "never lose, repeat or swap a byte" in {
    prove(new RoundTripFormal(2), 14)
  }
}
