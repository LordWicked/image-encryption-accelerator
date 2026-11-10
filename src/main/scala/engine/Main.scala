package engine

import chisel3._
import chiseltest._
import circt.stage.ChiselStage
import java.io.File
import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer

// MVP pipeline (issue #26), run with: sbt run   (or: sbt "run some/image.png 128 96 rgb")
//   1. ImageFile reads the image and scales it to the chosen size
//   2. the RGB pixels are streamed through Top (PixelFormatter -> Packer -> Ascon -> Unpacker) in simulation
//   3. the ciphertext is turned back into an image, the tag is saved apart
//   4. the Verilog of Top is written to generated/ for Vivado
object Main extends App {
  val image = if (args.nonEmpty) args(0) else "assets/128x128-pixel-art.jpg"
  val width = if (args.length > 1) args(1).toInt else 256
  val height = if (args.length > 2) args(2).toInt else 256
  val mode = if (args.length > 3) args(3) else "rgb"
  require(mode == "gray" || mode == "rgb", s"mode must be gray or rgb, got $mode")
  val channels = if (mode == "rgb") 3 else 1
  val roundsPerCycle = if (args.length > 4) args(4).toInt else 1
  val outDir = "output"
  new File(outDir).mkdirs()

  // fixed key and nonce for the MVP: bytes 00 01 .. 0f, little-endian (see README)
  val key = BigInt("0f0e0d0c0b0a09080706050403020100", 16)
  val nonce = BigInt("0f0e0d0c0b0a09080706050403020100", 16)

  // 1. image -> RGB pixels
  val pixels = ImageFile.load(image, width, height)

  // the bytes the hardware will encrypt, original.png and image.bin are there to compare the result with
  val plain =
    if (channels == 1) pixels.map(PixelFormatter.gray)
    else pixels.flatMap(p => Seq((p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff))
  Files.write(new File(s"$outDir/image.bin").toPath, plain.map(_.toByte).toArray)
  ImageFile.save(s"$outDir/original.png", plain, width, height, channels)

  // 2. stream the pixels through the hardware
  val result = ArrayBuffer[Int]()
  RawTester.test(new Top(width, height, channels, roundsPerCycle)) { dut =>
    dut.io.key.poke(key.U(128.W))
    dut.io.nonce.poke(nonce.U(128.W))
    dut.io.start.poke(true.B)
    dut.clock.step(1)
    dut.io.start.poke(false.B)

    // we always take the output, the core sends ciphertext and then the tag
    dut.io.out.ready.poke(true.B)
    var sent = 0
    var cycles = 0
    while (result.length < plain.length + 16) {
      dut.io.in.valid.poke((sent < pixels.length).B)
      dut.io.in.bits.poke(if (sent < pixels.length) pixels(sent).U else 0.U)

      // a pixel moves when valid and ready are both high at the clock edge
      if (sent < pixels.length && dut.io.in.ready.peek().litToBoolean) sent += 1
      if (dut.io.out.valid.peek().litToBoolean) result += dut.io.out.bits.peek().litValue.toInt

      dut.clock.step(1)
      cycles += 1
      require(cycles < 100 * plain.length, "simulation timeout, the core stopped sending")
    }
    println(s"Simulated $cycles clock cycles for ${width}x$height $mode, ${plain.length} bytes")
  }

  // 3. split ciphertext and tag, bytes -> image
  val cipher = result.take(plain.length).toSeq
  val tag = result.drop(plain.length)
  Files.write(new File(s"$outDir/encrypted.bin").toPath, cipher.map(_.toByte).toArray)
  Files.write(new File(s"$outDir/tag.bin").toPath, tag.map(_.toByte).toArray)
  ImageFile.save(s"$outDir/encrypted.png", cipher, width, height, channels)
  println("Tag: " + tag.map(b => f"$b%02x").mkString)

  // 4. Verilog for Vivado
  ChiselStage.emitSystemVerilogFile(
    new Top(width, height, channels),
    args = Array("--target-dir", "generated"),
    // cleaner Verilog: no random init blocks and no debug comments
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  println(s"Done: images in $outDir/, Verilog in generated/Top.sv")
}
