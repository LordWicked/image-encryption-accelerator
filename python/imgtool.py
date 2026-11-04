import sys
from PIL import Image

# Used when the size and mode are not given.
W = H = 64
MODE = "gray"

# Pillow name and bytes per pixel of each mode.
# The bytes are written row by row, in rgb mode as R G B for each pixel.
MODES = {"gray": ("L", 1), "rgb": ("RGB", 3)}


def png_to_bin(src_path, bin_path, w, h, mode):
    pil_mode, channels = MODES[mode]
    im = Image.open(src_path)

    if im.mode != pil_mode:
        im = im.convert(pil_mode)

    if im.size != (w, h):
        im = im.resize((w, h), Image.Resampling.LANCZOS)

    data = im.tobytes()
    size = w * h * channels
    assert len(data) == size, f"Expecting {size} bytes, obtained {len(data)}"

    with open(bin_path, "wb") as f:
        f.write(data)
    print(f"Success: {size} bytes ({channels} byte/pixel) stored in '{bin_path}'.")


def bin_to_png(bin_path, png_path, w, h, mode):
    pil_mode, channels = MODES[mode]
    with open(bin_path, "rb") as f:
        data = f.read()

    size = w * h * channels
    if len(data) != size:
        raise ValueError(f"Expecting {size} bytes, obtained {len(data)}")

    Image.frombytes(pil_mode, (w, h), data).save(png_path)
    print(f"Success: Image done and saved in '{png_path}'.")


if __name__ == "__main__":
    if len(sys.argv) not in (4, 6, 7):
        sys.exit("Use: python script.py <png2bin|bin2png> <origin> <destiny> [<width> <height> [gray|rgb]]")

    cmd, src, dest = sys.argv[1], sys.argv[2], sys.argv[3]
    w = int(sys.argv[4]) if len(sys.argv) > 4 else W
    h = int(sys.argv[5]) if len(sys.argv) > 4 else H
    mode = sys.argv[6] if len(sys.argv) > 6 else MODE

    if mode not in MODES:
        sys.exit(f"Mode '{mode}' not recognized. Use 'gray' or 'rgb'.")

    if cmd == "png2bin":
        png_to_bin(src, dest, w, h, mode)
    elif cmd == "bin2png":
        bin_to_png(src, dest, w, h, mode)
    else:
        sys.exit(f"Command '{cmd}' not recognized. Use 'png2bin' or 'bin2png'.")
