package engine

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import scala.collection.mutable.ArrayBuffer
import java.awt.Color

class ImagePipelineSpec extends AnyFlatSpec with ChiselScalatestTester {
  "PassThroughEngine" should "process a custom real image pixel by pixel" in {
    test(new PassThroughEngine) { dut =>

      // 1. load custom image 64x64 or 128x128 for faster simulation time

      val inputFile = new File("128x128-pixel-art.jpg")       //chose the name of the image (has to be in the directory)
      assert(inputFile.exists(), "Error: Please place an image named 'minha_foto.png' in the root folder.")
      val img = ImageIO.read(inputFile)

      val width = img.getWidth
      val height = img.getHeight

      // 2. extract pixels and convert to greyscale
      // since our hardware datapath is 8-bit (UInt(8.W)), it only supports grayscale
      // so we convert RGB to Grayscale before injecting it into the hardware
      val inPixels = ArrayBuffer[Int]()
      for (y <- 0 until height; x <- 0 until width) {
        val color = new Color(img.getRGB(x, y))
        // Standard luminance formula (weighted average of RGB)
        val gray = (color.getRed * 0.299 + color.getGreen * 0.587 + color.getBlue * 0.114).toInt
        inPixels += gray
      }

      // 3 strem to hardware and collect output
      val outPixels = ArrayBuffer[Int]()

      // tell the dut that the test environment is always ready to receive data
      dut.io.out.ready.poke(true.B)

      for (pixel <- inPixels) {
        // Feed the 8-bit grayscale pixel to the hardware
        dut.io.in.valid.poke(true.B)
        dut.io.in.bits.poke(pixel.U)

        // Advance the clock by one cycle
        dut.clock.step(1)

        // Check if there is valid output data
        if (dut.io.out.valid.peek().litToBoolean) {
          outPixels += dut.io.out.bits.peek().litValue.toInt
        }
      }

      // 4. RECONSTRUCT OUTPUT IMAGE
      // Create a new grayscale canvas and inject the hardware-processed pixels
      val outImg = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
      var i = 0
      for (y <- 0 until height; x <- 0 until width) {
        // Convert the single 8-bit value back to an RGB int for saving
        val grayValue = outPixels(i)
        val rgb = new Color(grayValue, grayValue, grayValue).getRGB
        outImg.setRGB(x, y, rgb)
        i += 1
      }

      ImageIO.write(outImg, "png", new File("hardware_image.png"))

      // 5. VALIDATE SUCCESS
      assert(inPixels == outPixels, "Hardware output did not match input pixels!")
    }
  }
}