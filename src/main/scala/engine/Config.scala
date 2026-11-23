package engine

object Config {
  val MaxPixels = 1024
  final val BlockSize = 16 // Ascon receives 128 bits as input by default
  final val key = "0f0e0d0c0b0a09080706050403020100"
  final val nounce = "0f0e0d0c0b0a09080706050403020100"
}
