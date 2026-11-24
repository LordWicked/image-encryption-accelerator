# used by video.py, decrypts the frames in its own process. pyascon takes ~40 ms per 64x64 frame
# and in the same process it would slow down the window and the serial port
# stdin: nonce + ciphertext + tag, stdout: 1 byte (1 = tag ok) + the frame (zeros if the tag failed)
import os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ascon  # official pyascon

frame = int(sys.argv[1])  # bytes per frame
KEY = bytes(range(16))  # fixed key, same as the board
try:
    while True:
        data = sys.stdin.buffer.read(16 + frame + 16)
        if len(data) < 16 + frame + 16:
            break
        plain = ascon.ascon_decrypt(KEY, data[:16], b"", data[16:])
        sys.stdout.buffer.write(b"\x01" + plain if plain is not None else bytes(1 + frame))
        sys.stdout.buffer.flush()
except (KeyboardInterrupt, OSError):
    pass
