package engine

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// the PC side of the pipeline: reading and writing image files
// (decoding a JPG or PNG is not something for the FPGA, it only gets raw pixels)
object ImageFile {
  // reads any image and scales it, returns the RGB pixels row by row, top-left first
  // each new pixel is the average of the source pixels it covers, so shrinking a
  // big image does not skip pixels and leave a dotted pattern
  def load(path: String, width: Int, height: Int): Seq[Int] = {
    val source = ImageIO.read(new File(path))
    require(source != null, s"$path is not an image")

    val (sourceWidth, sourceHeight) = (source.getWidth, source.getHeight)
    val rgb = source.getRGB(0, 0, sourceWidth, sourceHeight, null, 0, sourceWidth)

    // the source pixels from start until end, at least one when the image grows
    def covered(i: Int, size: Int, sourceSize: Int): Range = {
      val start = i * sourceSize / size
      start until math.max(start + 1, (i + 1) * sourceSize / size)
    }

    for (y <- 0 until height; x <- 0 until width) yield {
      val area = for (sy <- covered(y, height, sourceHeight); sx <- covered(x, width, sourceWidth))
        yield rgb(sy * sourceWidth + sx)
      def average(shift: Int): Int = area.map(p => (p >> shift) & 0xff).sum / area.length
      (average(16) << 16) | (average(8) << 8) | average(0)
    }
  }

  // writes bytes as a PNG: 1 byte per pixel for grayscale, R G B for each pixel otherwise
  def save(path: String, bytes: Seq[Int], width: Int, height: Int, channels: Int): Unit = {
    require(bytes.length == width * height * channels, s"expecting ${width * height * channels} bytes, got ${bytes.length}")

    val kind = if (channels == 1) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB
    val image = new BufferedImage(width, height, kind)
    // the raster takes the exact values, setRGB would adjust the gray levels
    for (y <- 0 until height; x <- 0 until width; c <- 0 until channels)
      image.getRaster.setSample(x, y, c, bytes((y * width + x) * channels + c))
    ImageIO.write(image, "png", new File(path))
  }
}
