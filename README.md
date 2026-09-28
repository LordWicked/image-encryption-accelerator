# image-encryption-accelerator
Group project implementing an image encryption/decryption accelerator using hardware generators written in Chisel.


## Current Progress & Architecture Status

We have successfully established the base verification and data-streaming pipeline for the Image Encryption/Decryption Accelerator.

- **Dataflow Interface (`PassThroughEngine.scala`)**: Implemented a decoupled streaming interface (`DecoupledIO` with 8-bit grayscale datapath) that establishes the hardware handshake protocol (`valid`/`ready`).
- **Testbench & Simulation (`ImagePipelineSpec.scala`)**: Created a complete ChiselTest verification environment that loads an input image, streams pixels cycle-by-cycle through the hardware simulation, and reconstructs the output image (`resultado_hardware.png`).

### Next Steps for the Group:
1. Integrate the encryption core (ChaCha20 or Ascon or other) into the middle of the streaming pipeline instead of the direct pass-through wires.
2. Extend the datapath if necessary to handle color channels or wider blocks.
