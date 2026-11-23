package engine
import _root_.circt.stage.ChiselStage

// verilog for the rounds per cycle evaluation, sbt "runMain engine.RoundsSweep"
// Top 256x256 RGB with 1, 2 and 4 rounds per cycle -> generated/rounds/k<k>/Top.sv
// and the Ascon core alone with the same blocks -> generated/core/k<k>/Ascon.sv
// fpga/ooc.tcl then gives area and max clock of each one
object RoundsSweep extends App {
  val blocks = 256 * 256 * 3 / 16
  for (k <- Seq(1, 2, 4)) {
    ChiselStage.emitSystemVerilogFile(
      new Top(256, 256, 3, 1, k),
      args = Array("--target-dir", s"generated/rounds/k$k"),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
    ChiselStage.emitSystemVerilogFile(
      new Ascon(blocks, k),
      args = Array("--target-dir", s"generated/core/k$k"),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
