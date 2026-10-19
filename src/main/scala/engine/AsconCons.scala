package engine

import chisel3._
import chisel3.util._

object AsconCons {
  val const_addition = {
    VecInit(
      "hf0".U(64.W),
      "he1".U(64.W),
      "hd2".U(64.W),
      "hc3".U(64.W),
      "hb4".U(64.W),
      "ha5".U(64.W),
      "h96".U(64.W),
      "h87".U(64.W),
      "h78".U(64.W),
      "h69".U(64.W),
      "h5a".U(64.W),
      "h4b".U(64.W)
    )
  }

  val s_box: Vec[UInt] = {
    VecInit(
      4.U(64.W),
      "hb".U(64.W),
      "h1f".U(64.W),
      14.U(64.W),
      "h1a".U(64.W),
      15.U(64.W),
      9.U(64.W),
      2.U(64.W),
      "h1b".U(64.W),
      5.U(64.W),
      8.U(64.W),
      12.U(64.W),
      "h1d".U(64.W),
      3.U(64.W),
      6.U(64.W),
      "h1c".U(64.W),
      "h1e".U(64.W),
      13.U(64.W),
      7.U(64.W),
      "he".U(64.W),
      0.U(64.W),
      "hd".U(64.W),
      11.U(64.W),
      18.U(64.W),
      10.U(64.W),
      "hc".U(64.W),
      1.U(64.W),
      19.U(64.W),
      16.U(64.W),
      "ha".U(64.W),
      "hf".U(64.W),
      17.U(64.W)
    )
  }
}
