# live video through the FPGA: camera or video file -> 64x64 RGB -> board -> PC
# the window has 4 panels: the source (whole picture, the yellow square is the part that goes to the
# board), the frame being sent, the last frame that came back (encrypted) and that frame decrypted on
# the PC, only shown if the tag is ok.
# The first 2 are the same frame. The encrypted one is behind by the trip to the board and back
# (send + encrypt + return) and the decrypted one also by the time the PC takes to decrypt it
#
#   python tools/video.py                    laptop camera
#   python tools/video.py some_video.mp4     a video file, it loops
#
# keys: q quit, t tamper on/off (flips 1 bit, the tag has to fail), s screenshot
# options: --frames N, --no-window, --port, --baud 3000000, --size 64
#
# needs the video bitstream (bitstreams/video_64x64_3M.bit). Every frame is a new image for the board
# so every frame gets its own nonce. If a byte gets lost on the way back the tag fails, then we stop
# sending, wait for the line to go quiet and start again. If one gets lost on the way to the board the
# tag is fine but the picture is not the one we sent, so that is checked too. The board is then out of
# step and needs BTNC
import argparse, collections, os, queue, subprocess, sys, threading, time
import cv2
import numpy as np
import serial

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from board_port import find_port

p = argparse.ArgumentParser()
p.add_argument("source", nargs="?", default="0", help="camera number (0 = laptop camera) or a video file")
p.add_argument("--port", default=None, help="serial port of the board, found automatically if not given")
p.add_argument("--baud", type=int, default=3000000, help="must match the bitstream")
p.add_argument("--size", type=int, default=64, help="width = height of the bitstream")
p.add_argument("--frames", type=int, default=0, help="stop after this many frames (0 = never)")
p.add_argument("--no-window", action="store_true")
args = p.parse_args()

W = H = args.size
FRAME = W * H * 3
PER = 16 + FRAME + 16  # the board sends nonce + ciphertext + tag per frame
IN_FLIGHT = 2  # frames sent but not back yet, keeps the uart busy both ways
WORKERS = 3  # decryption processes, every frame has its own nonce so they can go in parallel
BACKLOG = WORKERS + 1  # frames back but not decrypted, so it never falls behind
OUT = os.path.join(HERE, "..", "output", "video")

# ---------- video source, a thread keeps the newest frame ----------
camera = args.source.isdigit()
cap = cv2.VideoCapture(int(args.source), cv2.CAP_DSHOW if os.name == "nt" else cv2.CAP_ANY) if camera \
    else cv2.VideoCapture(args.source)
if not cap.isOpened():
    sys.exit(f"can not open {'camera ' if camera else ''}{args.source}")
file_fps = 0 if camera else (cap.get(cv2.CAP_PROP_FPS) or 30)
latest = None  # whole BGR frame
latest_lock = threading.Lock()
running = True


def square(f):
    # centre square, the part that goes to the board
    h, w = f.shape[:2]
    m = min(h, w)
    return f[(h - m) // 2:(h - m) // 2 + m, (w - m) // 2:(w - m) // 2 + m]


def capture():
    global latest
    t_next = time.perf_counter()
    while running:
        ok, f = cap.read()
        if not ok and not camera:  # end of the file, start again
            cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
            ok, f = cap.read()
        if not ok:
            time.sleep(0.01)
            continue
        with latest_lock:
            latest = cv2.flip(f, 1) if camera else f  # a mirror feels natural
        if file_fps:  # a file plays at its own speed, the camera sets its own
            t_next = max(t_next + 1 / file_fps, time.perf_counter() - 0.1)
            time.sleep(max(0.0, t_next - time.perf_counter()))


threading.Thread(target=capture, daemon=True).start()
t_wait = time.perf_counter()
while latest is None:
    if time.perf_counter() - t_wait > 5:
        sys.exit("no frames from the source")
    time.sleep(0.01)


def grab():
    # newest frame, whole and as W x H RGB for the board
    with latest_lock:
        f = latest
    small = cv2.resize(square(f), (W, H), interpolation=cv2.INTER_AREA)
    return f, cv2.cvtColor(small, cv2.COLOR_BGR2RGB)


# ---------- serial, one thread writes, one reads and one talks to each decryption process ----------
port = args.port or find_port()
s = serial.Serial(port, args.baud, timeout=0.3)
s.set_buffer_size(rx_size=1 << 22, tx_size=1 << 22)
s.reset_input_buffer()
workers = [subprocess.Popen([sys.executable, os.path.join(HERE, "decrypt_worker.py"), str(FRAME)],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE) for _ in range(WORKERS)]
cond = threading.Condition()  # protects the variables below
trips = collections.deque()  # (start of sending, bytes) of the frames in the board
waiting = 0  # frames back but not decrypted yet
paused = writing = False
epoch = 0  # goes up on a resync, so old frames are thrown away
number = 0  # of the last frame that came back
sending = None  # (whole frame, 64x64) being sent now
arrived = None  # (nonce, ciphertext) that came back last
back_q, done_q = queue.Queue(), queue.Queue()
changed = threading.Event()  # something new to show
lost = threading.Event()  # the board was unplugged or switched off
flush, flushed = threading.Event(), threading.Event()
tamper = False
t_heard = time.perf_counter()


def writer():
    global sending, writing
    while running:
        with cond:
            if paused or len(trips) >= IN_FLIGHT or waiting >= BACKLOG:
                cond.wait(0.2)
                continue
            writing = True
            sending = grab()
            data = sending[1].tobytes()
            trips.append((time.perf_counter(), data))
        changed.set()
        try:
            s.write(data)
        except serial.SerialException:
            lost.set()
        with cond:
            writing = False
            cond.notify_all()


def reader():
    global arrived, waiting, number, t_heard
    buf = bytearray()
    while running:
        try:
            got = s.read(1 << 16 if flush.is_set() else PER - len(buf))
        except (serial.SerialException, TypeError):  # TypeError if the port closes in the middle
            lost.set()
            return
        if flush.is_set():  # resync, throw everything away until the line is quiet
            buf.clear()
            if not got:
                flush.clear()
                flushed.set()
            continue
        buf += got
        if len(buf) < PER:
            continue
        now = t_heard = time.perf_counter()
        with cond:
            started, data = trips.popleft() if trips else (now, None)
            trip = now - started  # send + encrypt + return
            tampered = tamper
            if tampered:
                buf[16 + FRAME // 2] ^= 0x01  # one bit of one pixel in the middle
            arrived = (bytes(buf[:16]), bytes(buf[16:16 + FRAME]))
            waiting += 1
            number += 1
            back_q.put((epoch, number, bytes(buf), data, trip, tampered))
            cond.notify_all()
        buf.clear()
        changed.set()


def decrypter(worker):
    global waiting
    while running:
        try:
            e, n, data, original, trip, tampered = back_q.get(timeout=0.2)
        except queue.Empty:
            continue
        t = time.perf_counter()
        try:
            worker.stdin.write(data)
            worker.stdin.flush()
            answer = worker.stdout.read(1 + FRAME)
        except (OSError, ValueError):
            answer = b""
        if len(answer) < 1 + FRAME:
            done_q.put(None)  # the worker is gone
            changed.set()
            return
        done_q.put((e, n, answer[1:] if answer[0] else None, original, trip, time.perf_counter() - t, tampered))
        with cond:
            waiting -= 1
            cond.notify_all()
        changed.set()


def resync():
    global paused, epoch, t_heard
    with cond:
        paused = True
        while writing and not lost.is_set():
            cond.wait(0.2)
    flushed.clear()
    flush.set()
    flushed.wait(5)  # board finished its frames and the line is quiet
    with cond:
        trips.clear()
        epoch += 1
        paused = False
        t_heard = time.perf_counter()
        cond.notify_all()


# ---------- window ----------
PW = 320  # panel size on screen
GAP, TOP, BOTTOM = 16, 44, 84
WHITE, GREY, GREEN, RED, YELLOW = (255, 255, 255), (170, 170, 170), (90, 210, 120), (70, 70, 235), (60, 200, 230)
FONT = cv2.FONT_HERSHEY_SIMPLEX
SOURCE = "camera" if camera else "video file"
BLACK = np.zeros((PW, PW, 3), np.uint8)
# the source keeps its shape (16:9, 4:3, ...), a very wide or very tall one gets black bars
src_h, src_w = latest.shape[:2]
LIVE_W = int(round(PW * min(max(src_w / src_h, 9 / 16), 16 / 9)))


def big(rgb):
    return cv2.resize(cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), (PW, PW), interpolation=cv2.INTER_NEAREST)


def whole(frame):
    h, w = frame.shape[:2]
    k = min(LIVE_W / w, PW / h)
    fw, fh = min(int(round(w * k)), LIVE_W), min(int(round(h * k)), PW)
    panel = np.zeros((PW, LIVE_W, 3), np.uint8)
    x, y = (LIVE_W - fw) // 2, (PW - fh) // 2
    panel[y:y + fh, x:x + fw] = cv2.resize(frame, (fw, fh), interpolation=cv2.INTER_AREA)
    if w != h:
        m = min(fw, fh)
        x, y = (LIVE_W - m) // 2, (PW - m) // 2
        cv2.rectangle(panel, (x, y), (x + m - 1, y + m - 1), YELLOW, 2)
    return panel


def arrow(canvas, x1, x2, text, colour):
    # from the middle of one panel to the middle of the next, with the time under it
    y = TOP + PW + 14
    cv2.arrowedLine(canvas, (x1 + PW // 2 + 6, y), (x2 + PW // 2 - 6, y), colour, 2, cv2.LINE_AA, tipLength=0.04)
    tw = cv2.getTextSize(text, FONT, 0.55, 1)[0][0]
    cv2.putText(canvas, text, ((x1 + x2 + PW - tw) // 2, y + 24), FONT, 0.55, colour, 1, cv2.LINE_AA)


def compose(status, ok, plain, trip_ms, dec_ms):
    xs = [GAP] + [2 * GAP + LIVE_W + i * (PW + GAP) for i in range(3)]
    width = xs[3] + PW + GAP
    canvas = np.full((TOP + PW + BOTTOM, width, 3), 24, np.uint8)
    full, small = sending
    cipher = np.frombuffer(arrived[1], np.uint8).reshape(H, W, 3)
    panels = [(whole(full), SOURCE), (big(small), f"sending to the FPGA ({W}x{H})"),
              (big(cipher), "encrypted by the FPGA"),
              (big(plain) if plain is not None else BLACK, "decrypted on the PC")]
    for x, (img, label) in zip(xs, panels):
        canvas[TOP:TOP + PW, x:x + img.shape[1]] = img
        cv2.putText(canvas, label, (x, TOP - 14), FONT, 0.6, WHITE, 1, cv2.LINE_AA)
    arrow(canvas, xs[1], xs[2], f"send + encrypt + return: {trip_ms:.0f} ms", YELLOW)
    arrow(canvas, xs[2], xs[3], f"decrypt: {dec_ms:.0f} ms", GREY)
    if not ok:
        x = xs[3]
        cv2.putText(canvas, "TAG FAIL", (x + PW // 2 - 100, TOP + PW // 2), FONT, 1.3, RED, 3, cv2.LINE_AA)
        cv2.putText(canvas, "nothing is shown", (x + PW // 2 - 95, TOP + PW // 2 + 40), FONT, 0.7, RED, 1, cv2.LINE_AA)
    y = TOP + PW + 68
    cv2.putText(canvas, status, (GAP, y), FONT, 0.55, GREY, 1, cv2.LINE_AA)
    cv2.putText(canvas, "tag OK" if ok else "TAG FAIL", (width - GAP - 100, y), FONT, 0.7,
                GREEN if ok else RED, 2, cv2.LINE_AA)
    return canvas


def smooth(old, new):
    return 0.9 * old + 0.1 * new if old else new


# ---------- main loop ----------
frames, shown, fails, resyncs, bad_in_a_row = 0, 0, 0, 0, 0
period, trip_ms, dec_ms, last, t_start = 0.0, 0.0, 0.0, None, time.perf_counter()
ok, plain, canvas, code = True, None, None, 0
title = "Ascon on the FPGA - live video"
print(f"{W}x{H} RGB at {args.baud} baud on {port} from {'camera ' + args.source if camera else args.source}. "
      "q = quit, t = tamper, s = screenshot")
for target, a in [(writer, ()), (reader, ())] + [(decrypter, (w,)) for w in workers]:
    threading.Thread(target=target, args=a, daemon=True).start()
try:
    stop = False
    while not stop:
        changed.wait(0.02)
        new = changed.is_set()
        changed.clear()
        while not done_q.empty():
            result = done_q.get()
            if result is None:
                print("the decryption process stopped")
                stop, code = True, 1
                break
            e, n, data, original, trip, dec, tampered = result
            if e != epoch:
                continue  # from before a resync
            now = time.perf_counter()
            period = smooth(period, now - last) if last else 0.0
            last = now
            trip_ms, dec_ms = smooth(trip_ms, trip * 1000), smooth(dec_ms, dec * 1000)
            frames += 1
            good = data is not None
            if n > shown:  # they can finish out of order, never go back to an older frame
                shown, ok = n, good
                plain = np.frombuffer(data, np.uint8).reshape(H, W, 3) if good else None
            if good and data == original:
                bad_in_a_row = 0
            elif not tampered:  # real failure, the stream is out of step
                fails += 1
                bad_in_a_row += 1
                print("the tag failed" if not good else "the picture is not the one that was sent")
                if bad_in_a_row >= 3:
                    print("it keeps failing, press BTNC (centre button) on the board and run this again")
                    stop, code = True, 1
                    break
                resyncs += 1
                resync()
            if args.frames and frames >= args.frames:
                stop = True
                break
        if lost.is_set():
            print("lost the board, check the USB cable and the switch. If it was off program it again")
            code = 1
            break
        if time.perf_counter() - t_heard > 3:
            print("no answer from the board, trying again (if it repeats check it has the video bitstream, or press BTNC)")
            resyncs += 1
            resync()

        if new and arrived and sending:
            fps = 1 / period if period else 0.0
            status = (f"{fps:5.1f} fps   frame {frames}   {W}x{H} RGB, {args.baud / 1e6:g} Mbaud   "
                      f"nonce {arrived[0][:4].hex()}...   tamper {'ON' if tamper else 'off'} [t]   screenshot [s]   quit [q]")
            canvas = compose(status, ok, plain, trip_ms, dec_ms)
            if not args.no_window:
                cv2.imshow(title, canvas)
        if not args.no_window and canvas is not None:
            key = cv2.waitKey(1) & 0xFF
            if key in (ord("q"), 27) or cv2.getWindowProperty(title, cv2.WND_PROP_VISIBLE) < 1:
                break
            if key == ord("t"):
                tamper = not tamper
            if key == ord("s"):
                os.makedirs(OUT, exist_ok=True)
                cv2.imwrite(os.path.join(OUT, f"frame_{frames}.png"), canvas)
                print(f"saved output/video/frame_{frames}.png")
except KeyboardInterrupt:
    pass
finally:
    running = False
    total = time.perf_counter() - t_start
    if canvas is not None:
        os.makedirs(OUT, exist_ok=True)
        cv2.imwrite(os.path.join(OUT, "last_frame.png"), canvas)
    print(f"{frames} frames in {total:.1f} s = {frames / total:.1f} fps on average, "
          f"send + encrypt + return ~{trip_ms:.0f} ms, decrypt ~{dec_ms:.0f} ms, {fails} failures, {resyncs} resyncs")
    for w in workers:
        w.kill()
    time.sleep(0.4)  # the threads finish with the port before it closes
    cap.release()
    cv2.destroyAllWindows()
    s.close()
sys.exit(code)
