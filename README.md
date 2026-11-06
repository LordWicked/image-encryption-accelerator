# image-encryption-accelerator
Group project implementing an image encryption/decryption accelerator using hardware generators written in Chisel.
The image is read, encrypted using Ascon-AEAD128 and saved.
The target is an FPGA.

## Byte order

All multi-byte values use little-endian byte order, as specified for Ascon-AEAD128
(NIST SP 800-232): byte 0 is in bits 7..0. In Chisel, this is what `Vec(bytes).asUInt`
produces; in Python, `int.from_bytes(b, "little")`. The Ascon core contains no
byte-swapping logic.

| Value | Width | Format |
|---|---|---|
| Key, nonce | 128 bit | 16 bytes as one little-endian integer |
| Pixel beat | 64 bit | 8 pixels, pixel 0 in bits 7..0 |
| Ciphertext beat | 64 bit | same as pixel beat |
| Tag | 2 × 64 bit | beat 1 = tag bytes 0..7, beat 2 = tag bytes 8..15 |

**Stream order:** pixels row by row, top-left pixel first. Two beats form one
128-bit block: beat 1 goes into state word x0, beat 2 into x1. After the last
block, the core outputs the two tag beats.

### Example

Key = nonce = bytes `00 01 02 … 0f`:

    io.key = 0x0f0e0d0c0b0a09080706050403020100
    x1     = 0x0706050403020100   (key bytes 0..7)
    x2     = 0x0f0e0d0c0b0a0908   (key bytes 8..15)

Pixel bytes `00 01 … 0f` as one block:

    beat 1 = 0x0706050403020100   (pixels 0..7)
    beat 2 = 0x0f0e0d0c0b0a0908   (pixels 8..15)

Tag `a28657f6e82fe53d08b09eff9330bd2b` (for plaintext bytes `00 … 1f`):

    beat 1 = 0x3de52fe8f65786a2
    beat 2 = 0x2bbd3093ff9eb008

### Converting in Python

    key_int = int.from_bytes(key_bytes, "little")
    beats   = [int.from_bytes(block[i:i + 8], "little") for i in (0, 8)]
## Running the pipeline

Needs Python with Pillow (`pip install pillow`).

```
sbt run                                 # uses assets/128x128-pixel-art.jpg, 256x256 RGB
sbt "run path/to/image.png"             # any other image, 256x256 RGB
sbt "run path/to/image.png 128 96 rgb"  # width, height and gray or rgb
sbt "run path/to/image.png 64 64 gray"  # the MVP format, the fastest to simulate
```

The hardware is generated for one image format: `Top(width, height, channels)`,
with 1 channel for grayscale and 3 for RGB (bytes R, G, B of each pixel, in the
same 8-bit stream). The core has no padding yet, so `width * height * channels`
must be a multiple of 16 bytes. Any size with both sides a multiple of 4 works.

1. `python/imgtool.py` scales the image to that size and turns it into bytes.
2. The bytes go through `Top` (Packer -> Ascon -> Unpacker) in simulation,
   with the fixed MVP key and nonce (bytes `00 01 … 0f`).
3. The ciphertext is turned back into an image, the 16-byte tag is saved apart.
4. The Verilog of `Top` is written to `generated/Top.sv`.

Results in `output/`: `original.png`, `encrypted.png`, `encrypted.bin` and `tag.bin`.
