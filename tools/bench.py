# timing on the board: sends N images in one go, measures the round trip and checks every result
# with pyascon. Adds one line per run to a csv that evaluation/make_graphs.py uses
#
#   python tools/bench.py --baud 2000000 --size 256 --counts 1,2,4,8,16 --repeat 3
#   options: --images assets, --csv evaluation/data/board.csv, --port
#
# the bitstream has to match size and baud. The ready ones are 256x256 at 2 Mbaud and 64x64 at
# 3 Mbaud, for others build one (see the README).
# csv columns: baud;width;height;images;bytes_sent;bytes_back;seconds;all_ok
# close heavy programs like vivado while measuring, otherwise the PC itself loses bytes
import argparse, os, sys, time, threading
import serial
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ascon  # official pyascon
from board_port import find_port

p = argparse.ArgumentParser()
p.add_argument("--baud", type=int, default=2000000, help="must match the bitstream")
p.add_argument("--size", type=int, default=256, help="width = height of the bitstream")
p.add_argument("--counts", default="1", help="numbers of images per run, for example 1,2,4,8")
p.add_argument("--repeat", type=int, default=1, help="runs per count")
p.add_argument("--images", default=os.path.join(HERE, "..", "assets"), help="folder with the pictures to send")
p.add_argument("--csv", default=os.path.join(HERE, "..", "evaluation", "data", "board.csv"))
p.add_argument("--port", default=None, help="serial port of the board, found automatically if not given")
args = p.parse_args()

W = H = args.size
KEY = bytes(range(16))
photos = sorted(os.path.join(args.images, n) for n in os.listdir(args.images)
                if n.lower().endswith((".jpg", ".jpeg", ".png", ".bmp")))
if not photos:
    sys.exit(f"no pictures in {args.images}")
pics = [Image.open(p).convert("RGB").resize((W, H), Image.LANCZOS).tobytes() for p in photos]
per = 16 + W * H * 3 + 16  # nonce + ciphertext + tag

port = args.port or find_port()
with serial.Serial(port, args.baud, timeout=max(3, 40 * W * H * 3 / args.baud)) as s:
    s.set_buffer_size(rx_size=1 << 25, tx_size=1 << 25)
    for n in [int(c) for c in args.counts.split(",")]:
        for _ in range(args.repeat):
            imgs = [pics[i % len(pics)] for i in range(n)]
            data = b"".join(imgs)
            s.reset_input_buffer()
            t0 = time.perf_counter()
            w = threading.Thread(target=s.write, args=(data,))  # full duplex, like send_images.py
            w.start()
            back = bytearray()
            while len(back) < per * n:
                c = s.read(per * n - len(back))
                if not c:
                    break
                back += c
            w.join()
            t = time.perf_counter() - t0
            ok = len(back) == per * n and all(
                ascon.ascon_decrypt(KEY, bytes(back[i * per:i * per + 16]), b"",
                                    bytes(back[i * per + 16:(i + 1) * per])) == imgs[i]
                for i in range(n))
            line = f"{args.baud};{W};{H};{n};{len(data)};{len(back)};{t:.3f};{ok}"
            print(line, flush=True)
            with open(args.csv, "a") as f:
                f.write(line + "\n")
            if not ok:
                sys.exit("FAILED: press BTNC on the board (or program it again) and run again")
