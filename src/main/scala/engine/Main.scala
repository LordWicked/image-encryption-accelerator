package engine

import chisel3._
import chiseltest._
import circt.stage.ChiselStage
import java.io.File
import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer
import scala.sys.process._

// MVP pipeline (issue #26), run with: sbt run   (or: sbt "run some/image.png")
//   1. python/imgtool.py turns the image into raw grayscale bytes
//   2. the bytes are streamed through Top (Packer -> Ascon -> Unpacker) in simulation
//   3. the ciphertext is turned back into an image, the tag is saved apart
//   4. the Verilog of Top is written to generated/ for Vivado
// needs Python with Pillow (pip install pillow)
object Main extends App {
  val image = if (args.nonEmpty) args(0) else "assets/128x128-pixel-art.jpg"
  val outDir = "output"
  new File(outDir).mkdirs()

  // fixed key and nonce for the MVP: bytes 00 01 .. 0f, little-endian (see README)
  val key = BigInt("0f0e0d0c0b0a09080706050403020100", 16)
  val nonce = BigInt("0f0e0d0c0b0a09080706050403020100", 16)

  def python(args: String*): Unit = {
    val exitCode = (Seq("python", "python/imgtool.py") ++ args).!
    require(exitCode == 0, s"imgtool.py ${args.head} failed")
  }

  // 1. image -> bytes (original.png is the grayscale image that really goes in)
  python("png2bin", image, s"$outDir/image.bin")
  python("bin2png", s"$outDir/image.bin", s"$outDir/original.png")
  val pixels = Files.readAllBytes(new File(s"$outDir/image.bin").toPath).map(_ & 0xff)
  require(pixels.length % 16 == 0, "the image size must be a multiple of 16 bytes")
  val numBlocks = pixels.length / 16

  // 2. stream the bytes through the hardware
  val result = ArrayBuffer[Int]()
  RawTester.test(new Top(numBlocks)) { dut =>
    dut.io.key.poke(key.U(128.W))
    dut.io.nonce.poke(nonce.U(128.W))
    dut.io.start.poke(true.B)
    dut.clock.step(1)
    dut.io.start.poke(false.B)

    // we always take the output, the core sends ciphertext and then the tag
    dut.io.out.ready.poke(true.B)
    var sent = 0
    var cycles = 0
    while (result.length < pixels.length + 16) {
      dut.io.in.valid.poke((sent < pixels.length).B)
      dut.io.in.bits.poke(if (sent < pixels.length) pixels(sent).U else 0.U)

      // a byte moves when valid and ready are both high at the clock edge
      if (sent < pixels.length && dut.io.in.ready.peek().litToBoolean) sent += 1
      if (dut.io.out.valid.peek().litToBoolean) result += dut.io.out.bits.peek().litValue.toInt

      dut.clock.step(1)
      cycles += 1
      require(cycles < 100 * pixels.length, "simulation timeout, the core stopped sending")
    }
    println(s"Simulated $cycles clock cycles for ${pixels.length} pixels")
  }

  // 3. split ciphertext and tag, bytes -> image
  val cipher = result.take(pixels.length)
  val tag = result.drop(pixels.length)
  Files.write(new File(s"$outDir/encrypted.bin").toPath, cipher.map(_.toByte).toArray)
  Files.write(new File(s"$outDir/tag.bin").toPath, tag.map(_.toByte).toArray)
  python("bin2png", s"$outDir/encrypted.bin", s"$outDir/encrypted.png")
  println("Tag: " + tag.map(b => f"$b%02x").mkString)

  // 4. Verilog for Vivado
  ChiselStage.emitSystemVerilogFile(
    new Top(numBlocks),
    args = Array("--target-dir", "generated"),
    // cleaner Verilog: no random init blocks and no debug comments
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  println(s"Done: images in $outDir/, Verilog in generated/Top.sv")
}
