package engine

import chisel3._
import chisel3.util._

// top level of the MVP (issue #26): bytes -> Packer -> Ascon -> Unpacker -> bytes
// no UART yet, the image is streamed in simulation by Main
// numBlocks = image bytes / 16, so 256 for a 64x64 grayscale image
class Top(numBlocks: Int = 256) extends Module {
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
  val packer = Module(new Packer(nBytes = 8))
  val ascon = Module(new Ascon(numBlocks))
  val unpacker = Module(new Unpacker(nBytes = 8))

  ascon.io.start := io.start
  ascon.io.key := io.key
  ascon.io.nonce := io.nonce

  packer.io.in <> io.in
  ascon.io.pixels_in <> packer.io.out
  unpacker.io.in <> ascon.io.enc_pixels_out
  io.out <> unpacker.io.out
}
