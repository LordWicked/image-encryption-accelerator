# graphs 1-6 of the README from the data in data/, saved in graphs/, numbers go to summary.txt
# run this one first, then make_perf.py and make_core.py (they add to summary.txt)
# data/lanes and data/rounds are vivado reports, cycles.csv comes from CyclesSpec, board.csv from tools/bench.py
import csv, os, re
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
os.chdir(HERE)
os.makedirs("graphs", exist_ok=True)
TOTAL_LUT = 63400
IMG = 256 * 256 * 3

# colours
BLUE, ORANGE, AQUA, VIOLET = "#2a78d6", "#eb6834", "#1baf7a", "#6250d6"
INK, INK2, MUTED, GRID, SURF = "#0b0b0b", "#52514e", "#8a8984", "#e4e3df", "#fcfcfb"
plt.rcParams.update({
    "figure.facecolor": SURF, "axes.facecolor": SURF, "savefig.facecolor": SURF,
    "axes.edgecolor": GRID, "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2,
    "text.color": INK, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8,
    "axes.spines.top": False, "axes.spines.right": False, "font.size": 10,
    "axes.titlesize": 13, "axes.titleweight": "bold", "axes.titlelocation": "left",
    "legend.frameon": False, "lines.linewidth": 2, "axes.axisbelow": True,
})
summary = []


def save(fig, name):
    fig.tight_layout()
    fig.savefig(os.path.join("graphs", name), dpi=160)
    plt.close(fig)
    print("wrote", name)


def subtitle(ax, text):
    ax.text(0, 1.02, text, transform=ax.transAxes, color=INK2, fontsize=9, va="bottom")


def fit(xs, ys):
    k = len(xs); mx = sum(xs) / k; my = sum(ys) / k
    a = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)
    return a, my - a * mx


# ---------- board measurements ----------
board = [r for r in csv.reader(open("data/board.csv"), delimiter=";")]
board = [dict(baud=int(b), w=int(w), h=int(h), n=int(n), sent=int(s), back=int(bk), t=float(t), ok=ok == "True")
         for b, w, h, n, s, bk, t, ok in board]


def best(baud, w, n):
    ts = [r["t"] for r in board if r["baud"] == baud and r["w"] == w and r["n"] == n and r["ok"]]
    return min(ts) if ts else None


# ---------- 1. area vs number of lanes ----------
lanes = []  # (number of lanes, LUTs) from the place & route reports
for n in (1, 2, 5, 10):
    util = open(f"data/lanes/n{n}/util.txt").read()
    lanes.append((n, int(re.search(r"\| Slice LUTs\s*\|\s*(\d+)", util).group(1))))
a, b = fit([n for n, _ in lanes], [l for _, l in lanes])
n_full, n_80 = (TOTAL_LUT - b) / a, (0.8 * TOTAL_LUT - b) / a
fig, ax = plt.subplots(figsize=(9, 5.2))
xmax = int(n_full * 1.1)
ax.axhspan(0.8 * TOTAL_LUT, TOTAL_LUT, color=ORANGE, alpha=0.10, lw=0)
ax.axhline(TOTAL_LUT, color=INK2, lw=1, ls="--")
ax.text(1, TOTAL_LUT * 1.01, f"whole FPGA: {TOTAL_LUT} LUTs", color=INK2, fontsize=9, va="bottom")
ax.text(1, 0.8 * TOTAL_LUT * 1.01, "above 80%: hard to route", color=INK2, fontsize=9, va="bottom")
ax.plot([0, xmax], [b, a * xmax + b], color=BLUE, lw=2, alpha=0.5, label=f"fit: {a:.0f} LUTs per lane")
ax.scatter([n for n, _ in lanes], [l for _, l in lanes], s=60, color=BLUE, edgecolor=SURF, lw=2, zorder=5,
           label="measured (Vivado, place & route)")
for n, l in lanes:
    ax.annotate(f"{l}", (n, l), textcoords="offset points", xytext=(8, -4), fontsize=8, color=INK2)
ax.annotate(f"~{n_80:.0f} lanes (80%)", (n_80, 0.8 * TOTAL_LUT), textcoords="offset points", xytext=(-40, -28),
            fontsize=9, arrowprops=dict(arrowstyle="-", color=MUTED))
ax.annotate(f"~{n_full:.0f} lanes (100%)", (n_full, TOTAL_LUT), textcoords="offset points", xytext=(-110, 10),
            fontsize=9, arrowprops=dict(arrowstyle="-", color=MUTED))
ax.set_xlim(0, xmax); ax.set_ylim(0, TOTAL_LUT * 1.12)
ax.set_xlabel("parallel encryption lanes (one image each)"); ax.set_ylabel("LUTs")
ax.legend(loc="center right")
ax.set_title("Area grows linearly with the number of lanes", pad=22)
subtitle(ax, "Top 256x256 RGB on the Artix-7 100T. The image is streamed, so a lane costs the same at any resolution")
save(fig, "1_area_vs_lanes.png")
summary.append(f"[1] area: {a:.0f} LUTs per lane, fits ~{n_80:.0f} lanes at 80% and ~{n_full:.0f} at 100% of {TOTAL_LUT} LUTs")

# ---------- rounds per cycle: synthesis + simulation ----------
cyc = {(r["part"], int(r["roundsPerCycle"])): int(r["cycles"]) for r in csv.DictReader(open("data/cycles.csv"), delimiter=";")}
BLOCKS = 48
rounds = []
for k in (1, 2, 4):
    util = open(f"data/rounds/k{k}/util.txt").read()
    lut = int(re.search(r"\| Slice LUTs\s*\|\s*(\d+)", util).group(1))
    timing = open(f"data/rounds/k{k}/timing.txt").read()
    wns = float(re.search(r"WNS\(ns\).*?\n.*?\n\s*(-?\d+\.\d+)", timing).group(1))
    fmax = 1000 / (10 - wns)
    core = BLOCKS * 16 / cyc[("core", k)]  # bytes per cycle, core alone
    top = BLOCKS * 16 / cyc[("top", k)]  # bytes per cycle, whole Top (1 byte in/out interface)
    rounds.append(dict(k=k, lut=lut, wns=wns, fmax=fmax, core=core * fmax, top=top * fmax,
                       core_cpb=cyc[("core", k)] / BLOCKS, top_cpb=cyc[("top", k)] / BLOCKS))

# ---------- 2. where the bottleneck is ----------
uart = []
for baud in sorted({r["baud"] for r in board}):
    t = best(baud, 256, 1)
    if t:
        uart.append((f"UART {baud / 1e6:g} Mbaud" if baud >= 1e6 else f"UART {baud} baud", IMG / t / 1e6))
r1 = rounds[0]
# FPGA bars at the 100 MHz clock the board really uses (bytes per cycle x 100 MHz)
top100, core100 = r1["top"] / r1["fmax"] * 100, r1["core"] / r1["fmax"] * 100
bars = uart + [("whole Top on the FPGA", top100), ("Ascon core alone", core100)]
fig, ax = plt.subplots(figsize=(9, 4.8))
ys = range(len(bars))[::-1]
cols = [ORANGE] * len(uart) + [BLUE] * 2
ax.barh(list(ys), [v for _, v in bars], color=cols, height=0.6, edgecolor=SURF, lw=2)
ax.set_xscale("log")
ax.set_yticks(list(ys)); ax.set_yticklabels([n for n, _ in bars])
for y, (_, v) in zip(ys, bars):
    ax.text(v * 1.15, y, f"{v * 1000:.1f} KB/s" if v < 1 else f"{v:.0f} MB/s", va="center", fontsize=9, color=INK)
ax.set_xlim(min(v for _, v in bars) / 2, max(v for _, v in bars) * 6)
ax.set_xlabel("throughput, MB/s (log scale)")
ax.grid(axis="y", visible=False)
from matplotlib.patches import Patch
ax.legend(handles=[Patch(color=ORANGE, label="measured on the Nexys A7 (USB-UART)"),
                   Patch(color=BLUE, label="FPGA logic at the board clock, 100 MHz (simulated)")],
          loc="upper center", bbox_to_anchor=(0.5, -0.16), ncol=2)
ratio = top100 / max(v for _, v in uart)
ax.set_title("The UART is the bottleneck, not Ascon", pad=22)
subtitle(ax, f"Even the fastest UART is ~{ratio:.0f}x slower than the encryption circuit")
save(fig, "2_bottleneck.png")
summary.append("[2] throughput: " + ", ".join(f"{n} = {v * 1000:.1f} KB/s" for n, v in uart) +
               f"; at 100 MHz: Top = {top100:.0f} MB/s, Ascon core alone = {core100:.0f} MB/s."
               f" Top is ~{ratio:.0f}x faster than the best UART")

# ---------- 3. time per image vs baud rate ----------
pts = [(baud, best(baud, 256, 1)) for baud in sorted({r["baud"] for r in board if r["w"] == 256})]
pts = [(b_, t) for b_, t in pts if t]
fig, ax = plt.subplots(figsize=(9, 5))
xs = [100000 * 1.08 ** i for i in range(60)]
xs = [x for x in xs if x <= 3.2e6]
ax.plot([x / 1e6 for x in xs], [(IMG + 32) * 10 / x for x in xs], color=BLUE, alpha=0.5,
        label="theory: 10 bits per byte (start + 8 + stop)")
ax.scatter([b_ / 1e6 for b_, _ in pts], [t for _, t in pts], s=60, color=BLUE, edgecolor=SURF, lw=2, zorder=5,
           label="measured on the board")
for b_, t in pts:
    ax.annotate(f"{t:.2f} s", (b_ / 1e6, t), textcoords="offset points", xytext=(8, 6), fontsize=9)
ax.set_xscale("log"); ax.set_yscale("log")
ax.set_xticks([b_ / 1e6 for b_, _ in pts]); ax.set_xticklabels([f"{b_ / 1e6:.3g}" for b_, _ in pts])
ax.minorticks_off()
ax.set_xlabel("baud rate, Mbaud (log scale)"); ax.set_ylabel("seconds per 256x256 RGB image (log scale)")
ax.legend()
ax.set_title("Faster UART = faster images, almost exactly as predicted", pad=22)
subtitle(ax, "Round trip PC -> FPGA -> PC; sending and receiving happen at the same time")
save(fig, "3_time_vs_baud.png")
summary.append("[3] time per 256x256 RGB image: " + ", ".join(f"{b_} baud = {t:.2f} s" for b_, t in pts))

# ---------- 4. time vs number of images ----------
pts = sorted({r["n"] for r in board if r["baud"] == 2000000 and r["w"] == 256})
pts = [(n, best(2000000, 256, n)) for n in pts]
pts = [(n, t) for n, t in pts if t]
a4, b4 = fit([n for n, _ in pts], [t for _, t in pts])
fig, ax = plt.subplots(figsize=(9, 5))
mx = max(n for n, _ in pts)
ax.plot([0, mx], [b4, a4 * mx + b4], color=BLUE, alpha=0.5, label=f"fit: {a4:.3f} s per image")
ax.scatter([n for n, _ in pts], [t for _, t in pts], s=60, color=BLUE, edgecolor=SURF, lw=2, zorder=5,
           label="measured on the board")
for n, t in pts:
    ax.annotate(f"{t:.2f} s", (n, t), textcoords="offset points", xytext=(-14, 10), fontsize=9)
ax.set_xlim(0, mx * 1.05); ax.set_ylim(0, (a4 * mx + b4) * 1.15)
ax.set_xlabel("images sent in one go"); ax.set_ylabel("seconds")
ax.legend(loc="upper left")
ax.set_title("Many images: time grows linearly", pad=22)
subtitle(ax, "256x256 RGB at 2 Mbaud, every result checked against the official Ascon (pyascon)")
save(fig, "4_time_vs_images.png")
summary.append("[4] at 2 Mbaud: " + ", ".join(f"{n} images = {t:.2f} s" for n, t in pts) + f"; fit {a4:.3f} s per image")

# ---------- 5. time vs resolution ----------
pts = sorted({r["w"] for r in board if r["baud"] == 2000000})
pts = [(w, best(2000000, w, 1)) for w in pts]
pts = [(w, t) for w, t in pts if t]
fig, ax = plt.subplots(figsize=(9, 5))
names = [f"{w}x{w}" for w, _ in pts]
ax.bar(names, [t for _, t in pts], color=BLUE, width=0.55, edgecolor=SURF, lw=2)
for i, (w, t) in enumerate(pts):
    ax.text(i, t, f"{t * 1000:.0f} ms\n(~{1 / t:.0f} images/s)", ha="center", va="bottom", fontsize=9)
ax.set_ylim(0, max(t for _, t in pts) * 1.25)
ax.grid(axis="x", visible=False)
ax.set_xlabel("image resolution (RGB)"); ax.set_ylabel("seconds per image")
ax.set_title("Time follows the number of pixels", pad=22)
subtitle(ax, "2 Mbaud, one image, measured on the board. 4x the pixels = 4x the time")
save(fig, "5_time_vs_resolution.png")
summary.append("[5] at 2 Mbaud: " + ", ".join(f"{w}x{w} = {t * 1000:.0f} ms" for w, t in pts))

# ---------- 6. rounds per cycle ----------
fig, axs = plt.subplots(1, 3, figsize=(12, 4.8))
ks = [str(r["k"]) for r in rounds]
for ax, key, label, fmt in [(axs[0], "lut", "LUTs (area)", "{:.0f}"),
                            (axs[1], "fmax", "max clock, MHz", "{:.0f}"),
                            (axs[2], "core", "MB/s", "{:.0f}")]:
    if key == "core":
        x = range(len(ks)); w_ = 0.36
        ax.bar([i - w_ / 2 for i in x], [r["core"] for r in rounds], w_, color=BLUE, edgecolor=SURF, lw=2,
               label="Ascon core alone")
        ax.bar([i + w_ / 2 for i in x], [r["top"] for r in rounds], w_, color=AQUA, edgecolor=SURF, lw=2,
               label="whole Top (1 byte/cycle)")
        for i, r in enumerate(rounds):
            ax.text(i - w_ / 2, r["core"], f"{r['core']:.0f}", ha="center", va="bottom", fontsize=8)
            ax.text(i + w_ / 2, r["top"], f"{r['top']:.0f}", ha="center", va="bottom", fontsize=8)
        ax.set_xticks(list(x)); ax.set_xticklabels(ks)
        ax.legend(loc="upper left", fontsize=8)
        ax.set_ylim(0, max(r["core"] for r in rounds) * 1.3)
        ax.set_title("Throughput", fontsize=11)
    else:
        ax.bar(ks, [r[key] for r in rounds], color=BLUE, width=0.55, edgecolor=SURF, lw=2)
        for i, r in enumerate(rounds):
            ax.text(i, r[key], fmt.format(r[key]), ha="center", va="bottom", fontsize=9)
        ax.set_ylim(0, max(r[key] for r in rounds) * 1.2)
        ax.set_title("Area" if key == "lut" else "Max clock (from slack at 100 MHz)", fontsize=11)
    ax.set_xlabel("rounds per clock cycle"); ax.set_ylabel(label); ax.grid(axis="x", visible=False)
fig.suptitle("More rounds per cycle: the core gets faster, but the circuit gets bigger and its clock slower", x=0.01, ha="left",
             fontsize=13, fontweight="bold")
fig.text(0.01, 0.86, "Top 256x256 RGB, Vivado place & route + simulated cycles. 4 rounds/cycle fails timing at 100 MHz (slack -4.6 ns).\n"
         "The whole Top moves 1 byte per cycle, so it never gains: only the slower clock is left", fontsize=9, color=INK2)
fig.tight_layout(rect=(0, 0, 1, 0.84))
fig.savefig("graphs/6_rounds_per_cycle.png", dpi=160); plt.close(fig); print("wrote 6_rounds_per_cycle.png")
for r in rounds:
    summary.append(f"[6] {r['k']} round(s)/cycle: {r['lut']} LUTs, slack {r['wns']:.3f} ns -> fmax {r['fmax']:.0f} MHz, "
                   f"core {r['core_cpb']:.1f} cycles/block = {r['core']:.0f} MB/s, Top {r['top_cpb']:.1f} cycles/block = {r['top']:.0f} MB/s")

open("summary.txt", "w").write("\n".join(summary) + "\n")
print("\n".join(summary))
