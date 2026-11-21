# builds the BoardTop bitstream for the Nexys A7-100T without the GUI, takes ~6 min
# run it from the repo root after BoardMain wrote generated/board/BoardTop.sv
#   vivado -mode batch -source fpga/build.tcl
#   vivado -mode batch -source fpga/build.tcl -tclargs <folder>   (uses <folder>/BoardTop.sv)
# the bitstream and the area/timing/power reports end up in the same folder
# (power is the vivado estimate, not a measurement)
set dir generated/board
if {[llength $argv] > 0} { set dir [lindex $argv 0] }
set here [file dirname [file normalize [info script]]]

read_verilog -sv $dir/BoardTop.sv
read_xdc $here/nexys_a7.xdc
# Artix-7 100T, csg324 package, speed grade -1
synth_design -top BoardTop -part xc7a100tcsg324-1
opt_design
place_design
route_design
report_utilization -file $dir/util.txt
report_timing_summary -file $dir/timing.txt
report_power -file $dir/power.txt
write_bitstream -force $dir/BoardTop.bit
puts "BITSTREAM $dir/BoardTop.bit"
