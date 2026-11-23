package engine

import chisel3._
import chisel3.util._
import engine.algo.Ascon
import Config._
/*
This class performs the ascon algorithm encryption to a whole image in parallel.
First it receives 24 bits at a time to perform grayscale operation dependent on the generator
and then it waits using the packer for batches of 128 bits and execute the multiple ascon rounds inside
a clock cycle.
 */
class ImageLane(numBlocks: Int, channels: Int, roundsPerCycle: Int)
    extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val key = Input(UInt(128.W))
    val nonce = Input(UInt(128.W))

    val in = Flipped(Decoupled(UInt(24.W)))
    val out = Decoupled(UInt(8.W))
  })

  val formatter = Module(new PixelFormatter(channels))

  // the packers group the bytes into the blocks the Ascon core works on
  val ascon = Module(new Ascon(numBlocks, roundsPerCycle))
  val nBytes = ascon.io.pixels_in.bits.getWidth / 8
  val packer = Module(new Packer(nBytes))
  val unpacker = Module(new Unpacker(nBytes))

  ascon.io.start := io.start
  ascon.io.key := io.key
  ascon.io.nonce := io.nonce

  formatter.io.in <> io.in
  packer.io.in <> formatter.io.out
  ascon.io.pixels_in <> packer.io.out
  unpacker.io.in <> ascon.io.enc_pixels_out
  io.out <> unpacker.io.out
}

/*
  This class is responsible for executing multiple images encryption in "parallel".
  First it checks every input generator and then executes the Image lane operation for every image given
  in the input.
 */
class Top(
    width: Int = 64,
    height: Int = 64,
    channels: Int = 1,
    numImages: Int = 1,
    roundsPerCycle: Int = 1
) extends Module {
  require(width > 0 && height > 0, s"invalid image size ${width}x$height")
  require(
    channels == 1 || channels == 3,
    s"channels must be 1 (grayscale) or 3 (RGB), got $channels"
  )
  require(numImages > 0, s"numImages must be at least 1, got $numImages")

  val imageBytes = width * height * channels
  require(
    imageBytes % BlockSize == 0,
    s"a ${width}x$height image with $channels channel(s) is $imageBytes bytes, not a multiple of 16"
  )

  val numBlocks = imageBytes / BlockSize

  val io = IO(new Bundle {

    val start = Input(Bool())
    val key = Input(UInt(128.W))

    val nonce = Input(Vec(numImages, UInt(128.W)))
    val in = Vec(numImages, Flipped(Decoupled(UInt(24.W))))
    val out = Vec(numImages, Decoupled(UInt(8.W)))
  })

  /*
    Loop Unrolling, this can be done in parallel in one cycle since the
    blocks are independent due to how the ascon algorithm is performed (only takes 128 bits at a time)
    to do an encryption.
   */
  for (i <- 0 until numImages) {
    val lane = Module(new ImageLane(numBlocks, channels, roundsPerCycle))
    lane.io.start := io.start
    lane.io.key := io.key
    lane.io.nonce := io.nonce(i)
    lane.io.in <> io.in(i)
    io.out(i) <> lane.io.out
  }
}
