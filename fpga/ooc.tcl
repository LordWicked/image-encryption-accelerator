# out of context synth + place & route of one module, no pins, only the clock
# used for the evaluation (area, timing, power) of designs that don't fit the board pins
# run from the repo root:
#   vivado -mode batch -source fpga/ooc.tcl -tclargs <top> <file.sv> <report folder>
#   vivado -mode batch -source fpga/ooc.tcl -tclargs Top generated/rounds/k1/Top.sv evaluation/data/rounds/k1
lassign $argv top sv dir
if {$dir eq ""} {
  puts "ERROR: usage -tclargs <top> <file.sv> <report folder>"
  exit 1
}
set here [file dirname [file normalize [info script]]]
file mkdir $dir

read_verilog -sv $sv
read_xdc $here/clock.xdc
synth_design -top $top -part xc7a100tcsg324-1 -mode out_of_context
opt_design
place_design
route_design
report_utilization -file $dir/util.txt
report_timing_summary -file $dir/timing.txt
report_power -file $dir/power.txt
puts "REPORTS $dir"
