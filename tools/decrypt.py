# decrypts the .enc files from send_images.py on the PC with the official ascon
# writes output/board/decrypted/<name>_decrypted.png, or nothing if the tag does not match
#
#   python tools/decrypt.py                  every .enc in output/board/encrypted/
#   python tools/decrypt.py some/file.enc
#   python tools/decrypt.py --tamper         flips 1 bit of the ciphertext first, the tag has to fail
#   option: --size 256
import argparse, glob, os, sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ascon  # official pyascon

p = argparse.ArgumentParser()
p.add_argument("files", nargs="*", help=".enc files (default: all in output/board/encrypted/)")
p.add_argument("--tamper", action="store_true", help="flip one bit of the ciphertext before decrypting")
p.add_argument("--size", type=int, default=256, help="width = height of the images")
args = p.parse_args()
W = H = args.size
KEY = bytes(range(16))  # the fixed MVP key, the same one the board uses
OUT = os.path.join(HERE, "..", "output", "board")

files = args.files or sorted(glob.glob(os.path.join(OUT, "encrypted", "*.enc")))
if not files:
    sys.exit("no .enc files: run tools/send_images.py first")
os.makedirs(os.path.join(OUT, "decrypted"), exist_ok=True)

for path in files:
    data = bytearray(open(path, "rb").read())
    if len(data) != 16 + W * H * 3 + 16:
        print(f"{os.path.basename(path)}: {len(data)} bytes, not a {W}x{H} RGB image (use --size)")
        continue
    nonce, sealed = bytes(data[:16]), data[16:]  # sealed = ciphertext + tag
    if args.tamper:
        sealed[len(sealed) // 2] ^= 0x01  # one bit of one pixel in the middle
    plain = ascon.ascon_decrypt(KEY, nonce, b"", bytes(sealed))
    name = os.path.splitext(os.path.basename(path))[0]
    if plain is None:
        print(f"{name}: TAG DOES NOT MATCH, the data was changed or the key is wrong. Nothing written.")
        continue
    Image.frombytes("RGB", (W, H), plain).save(os.path.join(OUT, "decrypted", f"{name}_decrypted.png"))
    print(f"{name}: tag OK, saved output/board/decrypted/{name}_decrypted.png")
