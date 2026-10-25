package engine

import circt.stage.ChiselStage

// generates the Verilog of the whole design for Vivado (issue #26)
// run with: sbt run   ->   output in generated/Top.sv
object Main extends App {
  ChiselStage.emitSystemVerilogFile(
    new Top(),
    args = Array("--target-dir", "generated"),
    // cleaner Verilog: no random init blocks and no debug comments
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
