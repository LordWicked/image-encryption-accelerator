# programs the board over USB with a .bit file, run from the repo root
#   vivado -mode batch -source fpga/program.tcl -tclargs generated/board/BoardTop.bit
# the fpga forgets it when the board is switched off
set bit [lindex $argv 0]
if {$bit eq "" || ![file exists $bit]} {
  puts "ERROR: give the .bit file, for example -tclargs generated/board/BoardTop.bit"
  exit 1
}
open_hw_manager
connect_hw_server
open_hw_target
set dev [lindex [get_hw_devices xc7a100t*] 0]
if {$dev eq ""} {
  puts "ERROR: no xc7a100t found, is the board connected and on?"
  exit 1
}
current_hw_device $dev
set_property PROGRAM.FILE $bit $dev
program_hw_devices $dev
puts "PROGRAMMED $bit"
close_hw_manager
