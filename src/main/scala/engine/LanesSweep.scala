package engine

import _root_.circt.stage.ChiselStage

// verilog for parallel lanes, sbt "runMain engine.LanesSweep"
object LanesSweep extends App {
  for (n <- Seq(1, 2, 5, 10))
    ChiselStage.emitSystemVerilogFile(
      new Top(256, 256, 3, n),
      args = Array("--target-dir", s"generated/lanes/n$n"),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
}
