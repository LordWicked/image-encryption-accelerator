# makes a 10 s test video for video.py, for when there is no camera. Slow pan over a picture
# with a moving ball and the frame number, so something is always moving
#
#   python tools/make_test_video.py                 uses assets/128x128-pixel-art.jpg
#   python tools/make_test_video.py my_photo.jpg
# writes output/video/test.mp4, then run python tools/video.py output/video/test.mp4
import os, sys
import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
photo_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "assets", "128x128-pixel-art.jpg")
photo = cv2.imread(photo_path)
if photo is None:
    sys.exit(f"can not read {photo_path}")
S, FPS, N = 360, 30, 300
h, w = photo.shape[:2]
if w < h * 1.5:  # stretch it so there is something to pan over
    photo = cv2.resize(photo, (int(h * 1.8), h))
    h, w = photo.shape[:2]

out_dir = os.path.join(HERE, "..", "output", "video")
os.makedirs(out_dir, exist_ok=True)
out = cv2.VideoWriter(os.path.join(out_dir, "test.mp4"), cv2.VideoWriter_fourcc(*"mp4v"), FPS, (S, S))
for i in range(N):
    x = int((w - h) * (0.5 - 0.5 * np.cos(2 * np.pi * i / N)))  # pan left -> right -> left
    frame = cv2.resize(photo[:, x:x + h], (S, S))
    cx = int(S / 2 + S / 3 * np.cos(2 * np.pi * i / 90))
    cy = int(S / 2 + S / 3 * np.sin(2 * np.pi * i / 90))
    cv2.circle(frame, (cx, cy), 40, (40, 120, 255), -1)
    cv2.putText(frame, f"{i:03d}", (20, 60), cv2.FONT_HERSHEY_SIMPLEX, 1.8, (255, 255, 255), 5)
    out.write(frame)
out.release()
print("wrote output/video/test.mp4")
