package engine

import chisel3._
import chisel3.util._

object AsconCons {
  val roundConstants: Seq[Int] =
    Seq(0xf0, 0xe1, 0xd2, 0xc3, 0xb4, 0xa5, 0x96, 0x87, 0x78, 0x69, 0x5a, 0x4b)

  def const_addition(i: UInt): UInt = VecInit(roundConstants.map(_.U(64.W)))(i)

  val DOMAIN_SEP = "h8000000000000000".U(64.W) // bit 63 of x4

  // val s_box: Vec[UInt] = {
  //   VecInit(
  //     "h4".U(64.W),
  //     "hb".U(64.W),
  //     "h1f".U(64.W),
  //     "h14".U(64.W),
  //     "h1a".U(64.W),
  //     "h15".U(64.W),
  //     "h9".U(64.W),
  //     "h2".U(64.W),
  //     "h1b".U(64.W),
  //     "h5".U(64.W),
  //     "h8".U(64.W),
  //     "h12".U(64.W),
  //     "h1d".U(64.W),
  //     "h3".U(64.W),
  //     "h6".U(64.W),
  //     "h1c".U(64.W),
  //     "h1e".U(64.W),
  //     "h13".U(64.W),
  //     "h7".U(64.W),
  //     "he".U(64.W),
  //     "h0".U(64.W),
  //     "hd".U(64.W),
  //     "h11".U(64.W),
  //     "h18".U(64.W),
  //     "h10".U(64.W),
  //     "hc".U(64.W),
  //     "h1".U(64.W),
  //     "h19".U(64.W),
  //     "h16".U(64.W),
  //     "ha".U(64.W),
  //     "hf".U(64.W),
  //     "h17".U(64.W)
  //   )
  // }

}
