package engine

import chisel3._
import chisel3.util._
import os.Source.WritableSource

// top level of the MVP (issue #26): bytes -> Packer -> Ascon -> Unpacker -> bytes
// no UART yet, the image is streamed in simulation by Main
// generated for one image format: width x height pixels of channels bytes
// (1 = grayscale, 3 = RGB), the core itself only sees the number of blocks
class Top(width: Int = 64, height: Int = 64, channels: Int = 1) extends Module {
  require(width > 0 && height > 0, s"invalid image size ${width}x$height")
  require(channels == 1 || channels == 3, s"channels must be 1 (grayscale) or 3 (RGB), got $channels")

  // the core has no padding yet, so the image must fill whole 16 byte blocks
  val imageBytes = width * height * channels
  require(imageBytes % 16 == 0, s"a ${width}x$height image with $channels channel(s) is $imageBytes bytes, not a multiple of 16")
  val numBlocks = imageBytes / 16

  val io = IO(new Bundle {
    // one pulse starts a new encryption with this key and nonce
    val start = Input(Bool())
    val key = Input(UInt(128.W))
    val nonce = Input(UInt(128.W))

    // plaintext pixels in, ciphertext bytes out, followed by the 16 byte tag
    val in = Flipped(Decoupled(UInt(8.W)))
    val out = Decoupled(UInt(8.W))
  })

  // the Ascon core works on 64 bit words, so the packers group 8 bytes
  val ascon = Module(new Ascon(numBlocks))
  val nBytes = ascon.io.pixels_in.bits.getWidth / 8
  val packer = Module(new Packer(nBytes))
  val unpacker = Module(new Unpacker(nBytes))

  ascon.io.start := io.start
  ascon.io.key := io.key
  ascon.io.nonce := io.nonce

  packer.io.in <> io.in
  ascon.io.pixels_in <> packer.io.out
  unpacker.io.in <> ascon.io.enc_pixels_out
  io.out <> unpacker.io.out
}
