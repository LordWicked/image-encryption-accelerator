# image-encryption-accelerator
Group project implementing an image encryption/decryption accelerator using hardware generators written in Chisel.
The image is read, encrypted using Ascon-AEAD128 and saved.
The target is an FPGA.

## Project idea

An FPGA accelerator that encrypts an image while it streams through the hardware:
<!-- 
```
[ PC / image ] -> [ load link: UART / SD ] -> [ pixel stream ] -> [ Ascon core ] -> [ encrypted image ]
                  |------------------------ Nexys A7-100T FPGA ------------------------|
```
-->
- **Target board:** Digilent Nexys A7-100T (Artix-7).
- **Input:** a static test image loaded onto the board. The loading link is not decided yet; the board has both a USB-UART bridge and a microSD slot. A 128x128 grayscale image is 16 KB, which fits in on-chip block RAM.
- **Interface:** the core consumes and produces a ready/valid (`DecoupledIO`) byte stream, so it is independent of how the image reaches the board.
- **Cipher:** Ascon-AEAD128, the NIST lightweight-cryptography standard. Its rounds use only XOR/AND/NOT/rotations on a 320-bit state, which maps well to hardware. Pixels are packed into 16-byte blocks (the 128x128 image is 1,024 blocks). The encrypted image looks like noise, with no visible outline as ECB-style encryption would leave.

## Why a hardware generator

Chisel turns the design space into parameters, so we can explore it on the board without rewriting RTL:

- **Datapath width:** grayscale (8 bit) or color.
- **Rounds per clock cycle:** trades area against throughput.

Hardware and tests live in one Scala codebase, and the testbench checks the core against a software Ascon reference.

## MVP (simulation only)

1. An Ascon encryption core written in Chisel, in place of the pass-through in `PassThroughEngine`, keeping the same stream interface.
2. `ImagePipelineSpec` streams the 128x128 grayscale test image through the core in chiseltest.
3. The ciphertext matches a software Ascon reference byte for byte.
4. Software decryption recovers the original image exactly.

## Next steps (after the MVP)

- Decryption on the FPGA.
- Loading the image over UART or SD on the Nexys A7.
- Color images.
- Throughput tuning (rounds per cycle).

## Docs

- `docs/specificatinos.md`: course project requirements.
- `docs/project.md`: project's description  
- `docs/MVP.pdf`: idea presentation.
- `docs/chisel_cheatsheet.md`: Chisel reference.
