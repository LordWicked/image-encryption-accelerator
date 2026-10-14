# 128x128 8-bit grayscale PNG <-> raw binary 
import sys
from PIL import Image
W = H = 128

def png_to_bin(png, binf):
    im = Image.open(png)
    if im.size != (W, H):
        raise ValueError("expected %dx%d, got %s" % (W, H, im.size))
    if im.mode != "L":
        raise ValueError("expected 8-bit grayscale (mode L), got mode %s" % im.mode)
    data = im.tobytes()
    assert len(data) == W * H
    open(binf, "wb").write(data)


def bin_to_png(binf, png):
    data = open(binf, "rb").read()
    if len(data) != W * H:
        raise ValueError("expected %d bytes, got %d" % (W * H, len(data)))
    Image.frombytes("L", (W, H), data).save(png)


if __name__ == "__main__":
    cmd,src,dest = sys.argv[1], sys.argv[2], sys.argv[3]
    if cmd == "png2bin" and len(sys.argv) == 4: png_to_bin(src,dest)
    elif cmd == "bin2png" and len(sys.argv) == 4: bin_to_png(src,dest)
    else: sys.exit("cmd not recognized or incorrect number of arguments")