import sys
from PIL import Image

W = H = 64
# Images have a fixed size (for now) and are converted to grayscale for simplicity.
def png_to_bin(src_path, bin_path):

    im = Image.open(src_path)

    if im.mode != "L":
        im = im.convert("L")

    if im.size != (W, H):
        im = im.resize((W, H), Image.Resampling.LANCZOS)

    data = im.tobytes()
    assert len(data) == W * H, f"Expecting {W * H} bytes, obtained {len(data)}"

    with open(bin_path, "wb") as f:
        f.write(data)
    print(f"Success: {W * H} bytes (1 byte/pixel) stored in '{bin_path}'.")


def bin_to_png(bin_path, png_path):
    with open(bin_path, "rb") as f:
        data = f.read()

    if len(data) != W * H:
        raise ValueError(f"Expecting {W * H} bytes, obtained {len(data)}")

    Image.frombytes("L", (W, H), data).save(png_path)
    print(f"Success: Image done and saved in '{png_path}'.")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("Use: python script.py <png2bin|bin2png> <origin> <destiny>")

    cmd, src, dest = sys.argv[1], sys.argv[2], sys.argv[3]

    if cmd == "png2bin":
        png_to_bin(src, dest)
    elif cmd == "bin2png":
        bin_to_png(src, dest)
    else:
        sys.exit(f"Command '{cmd}' not recognized. Use 'png2bin' or 'bin2png'.")