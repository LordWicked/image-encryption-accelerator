package engine

import chisel3._
import chiseltest._
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class PixelFormatterSpec extends AnyFlatSpec with ChiselScalatestTester {
  // sends the pixels and collects the bytes, out.ready is low on every third cycle
  def stream(dut: PixelFormatter, pixels: Seq[Int], expected: Int): Seq[Int] = {
    val result = ArrayBuffer[Int]()
    var sent = 0
    var cycles = 0
    while (result.length < expected) {
      val ready = cycles % 3 != 2
      dut.io.out.ready.poke(ready.B)
      dut.io.in.valid.poke((sent < pixels.length).B)
      dut.io.in.bits.poke(if (sent < pixels.length) pixels(sent).U else 0.U)
      if (sent < pixels.length && dut.io.in.ready.peek().litToBoolean) sent += 1
      if (ready && dut.io.out.valid.peek().litToBoolean) result += dut.io.out.bits.peek().litValue.toInt
      dut.clock.step(1)
      cycles += 1
      assert(cycles < 1000, s"timeout, got ${result.length} of $expected bytes")
    }
    result.toSeq
  }

  "PixelFormatter" should "send R, G and B in order, also with backpressure" in {
    test(new PixelFormatter(channels = 3)) { dut =>
      val result = stream(dut, Seq(0x112233, 0x445566, 0xa0b0c0), 9)
      assert(result == Seq(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0xa0, 0xb0, 0xc0))
    }
  }

  it should "turn pixels into grayscale" in {
    test(new PixelFormatter(channels = 1)) { dut =>
      val result = stream(dut, Seq(0x000000, 0xffffff, 0xff0000, 0x00ff00, 0x0000ff, 0x808080), 6)
      assert(result == Seq(0, 255, 76, 149, 28, 128))
    }
  }

  it should "match the software grayscale for any colour" in {
    val random = new scala.util.Random(1)
    val pixels = Seq.fill(200)(random.nextInt(0x1000000))
    test(new PixelFormatter(channels = 1)) { dut =>
      assert(stream(dut, pixels, pixels.length) == pixels.map(PixelFormatter.gray))
    }
  }

  "ImageFile" should "keep the exact bytes when saving, in grayscale and RGB" in {
    val file = Files.createTempFile("imagefile", ".png").toFile
    for (channels <- Seq(1, 3)) {
      val bytes = Seq.tabulate(4 * 4 * channels)(i => (i * 17) % 256)
      ImageFile.save(file.getPath, bytes, 4, 4, channels)

      val raster = ImageIO.read(file).getRaster
      val saved = for (y <- 0 until 4; x <- 0 until 4; c <- 0 until channels) yield raster.getSample(x, y, c)
      assert(saved == bytes)
    }
    file.delete()
  }

  it should "scale an image to the requested size" in {
    val pixels = ImageFile.load("assets/128x128-pixel-art.jpg", 20, 8)
    assert(pixels.length == 20 * 8)
    assert(pixels.forall(p => p >= 0 && p <= 0xffffff))
  }

  it should "average the source pixels when shrinking and repeat them when growing" in {
    val file = Files.createTempFile("imagefile", ".png").toFile
    // a checkerboard of black and white in RGB, it must become plain gray and not black or white
    val checkers = Seq.tabulate(4, 4)((y, x) => if ((x + y) % 2 == 0) 0 else 200).flatten.flatMap(Seq.fill(3)(_))
    ImageFile.save(file.getPath, checkers, 4, 4, 3)
    assert(ImageFile.load(file.getPath, 2, 2) == Seq.fill(4)(0x646464))

    ImageFile.save(file.getPath, Seq(10, 20, 30, 40, 50, 60), 2, 1, 3)
    assert(ImageFile.load(file.getPath, 4, 1) == Seq(0x0a141e, 0x0a141e, 0x28323c, 0x28323c))
    file.delete()
  }

  it should "refuse a file that is not an image" in {
    val file = Files.createTempFile("imagefile", ".png").toFile
    Files.write(file.toPath, "not an image".getBytes)
    intercept[IllegalArgumentException] { ImageFile.load(file.getPath, 4, 4) }
    file.delete()
  }
}
