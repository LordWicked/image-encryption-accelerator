## Pin constraints for the Digilent Nexys A7-100T, matching the ports of generated/Top.sv
## Pins taken from the official Digilent Nexys-A7-100T-Master.xdc

## Configuration bank voltage
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]

## 100 MHz clock
set_property -dict { PACKAGE_PIN E3  IOSTANDARD LVCMOS33 } [get_ports { clock }];
create_clock -add -name sys_clk_pin -period 10.00 -waveform {0 5} [get_ports { clock }];

## Reset: centre button BTNC (active high, like the Chisel reset)
set_property -dict { PACKAGE_PIN N17 IOSTANDARD LVCMOS33 } [get_ports { reset }];

## USB-UART bridge (names on the board are from the PC's point of view)
## UART_TXD_IN: PC -> FPGA
set_property -dict { PACKAGE_PIN C4  IOSTANDARD LVCMOS33 } [get_ports { io_rxd }];
## UART_RXD_OUT: FPGA -> PC
set_property -dict { PACKAGE_PIN D4  IOSTANDARD LVCMOS33 } [get_ports { io_txd }];
