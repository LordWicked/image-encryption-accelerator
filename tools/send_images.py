# sends one or more images through the board, one after the other, and checks every result
# needs the images bitstream (bitstreams/images_256x256_2M.bit)
#
#   python tools/send_images.py photo.jpg
#   python tools/send_images.py a.jpg b.png c.jpg
#   python tools/send_images.py assets              every image in the folder
#   options: --port (found automatically), --baud 2000000, --size 256, they must match the bitstream
#
# results go to output/board/
#   encrypted/<n>_<name>_encrypted.png   ciphertext shown as an image
#   encrypted/<n>_<name>.enc             nonce + ciphertext + tag as the board sent them, for decrypt.py
#   side_by_side/<n>_<name>.png          original, encrypted on the FPGA, decrypted on the PC
# the images are resized here on the PC, the board only gets the RGB bytes.
# If something goes wrong press BTNC (centre button) on the board and send again
import argparse, os, sys, time, threading
import serial
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ascon  # official pyascon
from board_port import find_port

p = argparse.ArgumentParser()
p.add_argument("images", nargs="+", help="image files or folders")
p.add_argument("--port", default=None, help="serial port of the board, found automatically if not given")
p.add_argument("--baud", type=int, default=2000000, help="must match the bitstream")
p.add_argument("--size", type=int, default=256, help="width = height of the bitstream")
args = p.parse_args()
W = H = args.size
OUT = os.path.join(HERE, "..", "output", "board")
ENC, SIDE = os.path.join(OUT, "encrypted"), os.path.join(OUT, "side_by_side")

# fixed key 00 01 .. 0f, same as the board. The nonce is not secret, the board sends it before
# every image (starts at 00 01 .. 0f and goes up by 1 per image)
KEY = bytes(range(16))

paths = []
for arg in args.images:
    if os.path.isdir(arg):
        paths += sorted(os.path.join(arg, n) for n in os.listdir(arg)
                        if n.lower().endswith((".jpg", ".jpeg", ".png", ".bmp")))
    else:
        paths.append(arg)
if not paths:
    sys.exit("no images found")
images = [Image.open(p).convert("RGB").resize((W, H), Image.LANCZOS) for p in paths]
data = b"".join(im.tobytes() for im in images)
per_image = 16 + W * H * 3 + 16  # nonce + ciphertext + tag
total = per_image * len(images)

port = args.port or find_port()
with serial.Serial(port, args.baud, timeout=5) as s:
    s.set_buffer_size(rx_size=1 << 24, tx_size=1 << 24)
    s.reset_input_buffer()
    t0 = time.perf_counter()
    # write and read at the same time, the board answers while the image is still arriving
    writer = threading.Thread(target=s.write, args=(data,))
    writer.start()
    back = bytearray()
    while len(back) < total:
        chunk = s.read(total - len(back))
        if not chunk:
            break
        back += chunk
    writer.join()
    t = time.perf_counter() - t0

print(f"{len(images)} image(s) of {W}x{H} RGB at {args.baud} baud on {port}: got {len(back)} of {total} bytes in {t:.2f} s")
os.makedirs(ENC, exist_ok=True)
os.makedirs(SIDE, exist_ok=True)
try:
    font = ImageFont.load_default(size=max(12, W // 16))
except TypeError:  # older pillow
    font = ImageFont.load_default()
failed = 0
for i, (path, im) in enumerate(zip(paths, images)):
    got = bytes(back[i * per_image:(i + 1) * per_image])
    if len(got) < per_image:
        print(f"[{i}] {os.path.basename(path)}: incomplete, is the bitstream for {W}x{H} at {args.baud} baud? "
              "Press BTNC on the board and try again")
        failed += 1
        continue
    nonce, cipher, tag = got[:16], got[16:-16], got[-16:]
    dec = ascon.ascon_decrypt(KEY, nonce, b"", got[16:])
    ok = dec is not None and dec == im.tobytes()
    failed += not ok
    print(f"[{i}] {os.path.basename(path)}: nonce {nonce.hex()}  tag {tag.hex()}  "
          f"{'decrypted == original' if ok else 'TAG FAIL, press BTNC and send again'}")

    name = f"{i}_{os.path.splitext(os.path.basename(path))[0]}"
    enc = Image.frombytes("RGB", (W, H), cipher)
    enc.save(os.path.join(ENC, f"{name}_encrypted.png"))
    with open(os.path.join(ENC, f"{name}.enc"), "wb") as e:
        e.write(got)
    top = max(34, W // 8)
    sbs = Image.new("RGB", (3 * W + 40, H + top + 10), "white")
    draw = ImageDraw.Draw(sbs)
    for j, (pic, label) in enumerate([(im, "original (PC)"), (enc, "encrypted on the FPGA"),
                                      (Image.frombytes("RGB", (W, H), dec) if dec else enc, "decrypted on the PC")]):
        sbs.paste(pic, (10 + j * (W + 10), top))
        draw.text((10 + j * (W + 10), top // 4), label, fill="black", font=font)
    sbs.save(os.path.join(SIDE, f"{name}.png"))
print(f"results in {os.path.normpath(OUT)}")
sys.exit(1 if failed else 0)
