package engine

import chisel3._
import chiseltest._
import circt.stage.ChiselStage
import java.io.File
import java.nio.file.Files
import scala.collection.mutable.ArrayBuffer

// MVP pipeline (issue #26), run with: sbt run   (or: sbt "run a.png,b.png 128 96 rgb")
//   1. ImageFile reads each image and scales it to the chosen size
//   2. the RGB pixels are streamed through Top in simulation, all images at the same time
//   3. the ciphertext is turned back into an image, the tag is saved apart
//   4. the Verilog of Top is written to generated/ for Vivado
object Main extends App {
  // one image, or several separated by commas: the hardware gets one lane for each
  val images = if (args.nonEmpty) args(0).split(",").toSeq else Seq("assets/128x128-pixel-art.jpg")
  val width = if (args.length > 1) args(1).toInt else 256
  val height = if (args.length > 2) args(2).toInt else 256
  val mode = if (args.length > 3) args(3) else "rgb"
  require(mode == "gray" || mode == "rgb", s"mode must be gray or rgb, got $mode")
  val channels = if (mode == "rgb") 3 else 1
  val roundsPerCycle = if (args.length > 4) args(4).toInt else 1
  val numImages = images.length
  val outDir = "output"
  new File(outDir).mkdirs()

  // fixed key and nonce for the MVP: bytes 00 01 .. 0f, little-endian (see README)
  val key = BigInt("0f0e0d0c0b0a09080706050403020100", 16)
  val nonce = BigInt("0f0e0d0c0b0a09080706050403020100", 16)
  // a nonce must never repeat with the same key, so image i gets nonce + i
  val nonces = Seq.tabulate(numImages)(nonce + _)

  // output/encrypted.png for one image, output/encrypted_0.png, _1 .. for several
  def outFile(name: String, i: Int, extension: String): String =
    if (numImages == 1) s"$outDir/$name.$extension" else s"$outDir/${name}_$i.$extension"

  // 1. images -> RGB pixels
  val pixels = images.map(ImageFile.load(_, width, height))

  // the bytes the hardware will encrypt, original.png and image.bin are there to compare the result with
  val plain = pixels.map { image =>
    if (channels == 1) image.map(PixelFormatter.gray)
    else image.flatMap(p => Seq((p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff))
  }
  for (i <- 0 until numImages) {
    Files.write(new File(outFile("image", i, "bin")).toPath, plain(i).map(_.toByte).toArray)
    ImageFile.save(outFile("original", i, "png"), plain(i), width, height, channels)
  }
  val imageBytes = plain.head.length

  // 2. stream the pixels through the hardware
  val results = Seq.fill(numImages)(ArrayBuffer[Int]())
  RawTester.test(new Top(width, height, channels, roundsPerCycle, numImages)) { dut =>
    dut.io.key.poke(key.U(128.W))
    for (i <- 0 until numImages) dut.io.nonce(i).poke(nonces(i).U(128.W))
    dut.io.start.poke(true.B)
    dut.clock.step(1)
    dut.io.start.poke(false.B)

    // we always take the output, the core sends ciphertext and then the tag
    dut.io.out.foreach(_.ready.poke(true.B))
    val sent = Array.fill(numImages)(0)
    var cycles = 0
    while (results.exists(_.length < imageBytes + 16)) {
      for (i <- 0 until numImages) {
        val (in, out) = (dut.io.in(i), dut.io.out(i))
        in.valid.poke((sent(i) < pixels(i).length).B)
        in.bits.poke(if (sent(i) < pixels(i).length) pixels(i)(sent(i)).U else 0.U)

        // a pixel moves when valid and ready are both high at the clock edge
        if (sent(i) < pixels(i).length && in.ready.peek().litToBoolean) sent(i) += 1
        if (out.valid.peek().litToBoolean) results(i) += out.bits.peek().litValue.toInt
      }

      dut.clock.step(1)
      cycles += 1
      require(cycles < 100 * imageBytes, "simulation timeout, the core stopped sending")
    }
    println(s"Simulated $cycles clock cycles for $numImages image(s) of ${width}x$height $mode, $imageBytes bytes each")
  }

  // 3. split ciphertext and tag, bytes -> image
  for (i <- 0 until numImages) {
    val cipher = results(i).take(imageBytes).toSeq
    val tag = results(i).drop(imageBytes)
    Files.write(new File(outFile("encrypted", i, "bin")).toPath, cipher.map(_.toByte).toArray)
    Files.write(new File(outFile("tag", i, "bin")).toPath, tag.map(_.toByte).toArray)
    ImageFile.save(outFile("encrypted", i, "png"), cipher, width, height, channels)
    println(s"Tag of ${images(i)}: " + tag.map(b => f"$b%02x").mkString)
  }

  // 4. Verilog for Vivado
  ChiselStage.emitSystemVerilogFile(
    new Top(width, height, channels, numImages),
    args = Array("--target-dir", "generated"),
    // cleaner Verilog: no random init blocks and no debug comments
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  println(s"Done: images in $outDir/, Verilog in generated/Top.sv")
}
