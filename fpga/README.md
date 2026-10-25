# FPGA build

## Generating the Verilog

```
sbt run
```

Writes `generated/Top.sv`, the whole design (UART RX -> core -> UART TX) for Vivado.
Use it together with the pin constraints in `NexysA7.xdc` (Nexys A7-100T).
The core inside `Top` is a parameter; for now it is the `PassthroughCore`.
