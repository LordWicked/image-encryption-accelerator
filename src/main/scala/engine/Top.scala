package engine

import chisel3._
import chisel3.util._

// one encryption lane: RGB pixels -> PixelFormatter -> Packer -> Ascon -> Unpacker -> bytes
class ImageLane(numBlocks: Int, channels: Int, roundsPerCycle: Int) extends Module {
  val io = IO(new Bundle {
    val start = Input(Bool())
    val key = Input(UInt(128.W))
    val nonce = Input(UInt(128.W))

    // RGB pixels in, ciphertext bytes out, followed by the 16 byte tag
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

// top level: numImages lanes side by side, each one encrypts its own image at the same time
// no UART yet, the images are streamed in simulation by Main
// generated for one image format: width x height pixels of channels bytes
// (1 = grayscale, 3 = RGB), the core itself only sees the number of blocks
class Top(width: Int = 64, height: Int = 64, channels: Int = 1, numImages: Int = 1, roundsPerCycle: Int = 1) extends Module {
  require(width > 0 && height > 0, s"invalid image size ${width}x$height")
  require(channels == 1 || channels == 3, s"channels must be 1 (grayscale) or 3 (RGB), got $channels")
  require(numImages > 0, s"numImages must be at least 1, got $numImages")

  // the core has no padding yet, so the image must fill whole 16 byte blocks
  val imageBytes = width * height * channels
  require(imageBytes % 16 == 0, s"a ${width}x$height image with $channels channel(s) is $imageBytes bytes, not a multiple of 16")
  val numBlocks = imageBytes / 16

  val io = IO(new Bundle {
    // one pulse starts a new encryption of all images with this key
    val start = Input(Bool())
    val key = Input(UInt(128.W))

    // one nonce and one stream for each image, a nonce must never repeat with the same key
    val nonce = Input(Vec(numImages, UInt(128.W)))
    val in = Vec(numImages, Flipped(Decoupled(UInt(24.W))))
    val out = Vec(numImages, Decoupled(UInt(8.W)))
  })

  for (i <- 0 until numImages) {
    val lane = Module(new ImageLane(numBlocks, channels, roundsPerCycle))
    lane.io.start := io.start
    lane.io.key := io.key
    lane.io.nonce := io.nonce(i)
    lane.io.in <> io.in(i)
    io.out(i) <> lane.io.out
  }
}
