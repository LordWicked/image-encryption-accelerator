# uart echo test, needs the echo bitstream (the board sends every byte back)
# sends random bytes and checks they come back the same. Writing runs in its own thread so we
# read at the same time, the board already answers while the data is still arriving
#
#   python tools/echo_test.py                    1000 bytes at 2 Mbaud
#   python tools/echo_test.py --count 1000000
#   python tools/echo_test.py --port COM5 --baud 115200
import argparse, os, sys, time, threading
import serial
from board_port import find_port

p = argparse.ArgumentParser()
p.add_argument("--port", default=None, help="serial port of the board, found automatically if not given")
p.add_argument("--baud", type=int, default=2000000, help="must match the bitstream")
p.add_argument("--count", type=int, default=1000, help="number of bytes to send")
args = p.parse_args()

port = args.port or find_port()
data = os.urandom(args.count)
with serial.Serial(port, args.baud, timeout=3) as s:
    s.set_buffer_size(rx_size=1 << 20, tx_size=1 << 20)
    s.reset_input_buffer()
    t0 = time.perf_counter()
    writer = threading.Thread(target=s.write, args=(data,))
    writer.start()
    back = bytearray()
    while len(back) < args.count:
        chunk = s.read(args.count - len(back))
        if not chunk:
            break
        back += chunk
    writer.join()
    t = time.perf_counter() - t0
ok = bytes(back) == data
print(f"{port}: sent {args.count}, got {len(back)}, equal: {ok}, {t:.2f} s, {args.count / t / 1000:.1f} kB/s")
sys.exit(0 if ok else 1)
