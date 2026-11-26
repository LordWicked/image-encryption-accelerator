# graphs 7 and 8: 1, 2 and 4 rounds per cycle compared on more than throughput (area, clock,
# latency, power, energy, efficiency) and where the time of one image goes on the board.
# run after make_graphs.py, uses data/rounds, cycles.csv, board.csv and board_power.txt
import csv, os, re
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

os.chdir(os.path.dirname(os.path.abspath(__file__)))
BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"
INK, INK2, MUTED, GRID, SURF = "#0b0b0b", "#52514e", "#8a8984", "#e4e3df", "#fcfcfb"
plt.rcParams.update({
    "figure.facecolor": SURF, "axes.facecolor": SURF, "savefig.facecolor": SURF,
    "axes.edgecolor": GRID, "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2,
    "text.color": INK, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8, "axes.axisbelow": True,
    "axes.spines.top": False, "axes.spines.right": False, "font.size": 10,
    "axes.titlesize": 11, "axes.titleweight": "bold", "axes.titlelocation": "left", "legend.frameon": False,
})


def num(pattern, text):
    return float(re.search(pattern, text).group(1))


cyc = {(r["part"], int(r["roundsPerCycle"])): r for r in csv.DictReader(open("data/cycles.csv"), delimiter=";")}
BLOCKS = 48
rows = []
for k in (1, 2, 4):
    util = open(f"data/rounds/k{k}/util.txt").read()
    timing = open(f"data/rounds/k{k}/timing.txt").read()
    power = open(f"data/rounds/k{k}/power.txt").read()
    lut = num(r"\| Slice LUTs\s*\|\s*(\d+)", util)
    ff = num(r"\| Slice Registers\s*\|\s*(\d+)", util)
    wns = num(r"WNS\(ns\).*?\n.*?\n\s*(-?\d+\.\d+)", timing)
    fmax = 1000 / (10 - wns)
    dyn = num(r"\| Dynamic \(W\)\s*\|\s*([\d.]+)", power)
    top = cyc[("top", k)]
    mbs = BLOCKS * 16 / int(top["cycles"]) * fmax  # whole Top at its own fmax
    mbs100 = BLOCKS * 16 / int(top["cycles"]) * 100 if wns >= 0 else 0  # at the board clock, if it fits
    rows.append(dict(k=k, lut=lut, ff=ff, fmax=fmax, wns=wns, mbs=mbs, mbs100=mbs100,
                     lat=int(top["firstOut"]) * 1000 / fmax,  # ns until the first ciphertext byte
                     dyn=dyn * 1000,  # mW at 100 MHz (Vivado estimate, default activity)
                     nj=dyn / (mbs100 * 1e6) * 1e9 if mbs100 else None,  # nJ per byte at 100 MHz
                     eff=mbs / lut * 1000))  # MB/s per 1000 LUTs

ks = [str(r["k"]) for r in rows]
panels = [
    ("lut", "Area", "LUTs", "{:.0f}", "lower is better"),
    ("fmax", "Max clock", "MHz", "{:.0f}", "higher is better"),
    ("mbs", "Throughput (whole Top)", "MB/s at its max clock", "{:.0f}", "higher is better"),
    ("lat", "Latency to 1st encrypted byte", "ns at its max clock", "{:.0f}", "lower is better"),
    ("dyn", "Dynamic power at 100 MHz", "mW (Vivado estimate)", "{:.0f}", "lower is better"),
    ("eff", "Efficiency", "MB/s per 1000 LUTs", "{:.0f}", "higher is better"),
]
fig, axs = plt.subplots(2, 3, figsize=(13, 7.6))
for ax, (key, title, unit, fmt, better) in zip(axs.flat, panels):
    vals = [r[key] for r in rows]
    cols = [BLUE if i == 0 else "#a9c8ee" for i in range(len(rows))]
    ax.bar(ks, vals, color=cols, width=0.55, edgecolor=SURF, lw=2)
    for i, v in enumerate(vals):
        ax.text(i, v, fmt.format(v), ha="center", va="bottom", fontsize=9)
    ax.set_ylim(0, max(vals) * 1.25)
    ax.set_title(f"{title}  ({better})")
    ax.set_ylabel(unit); ax.set_xlabel("rounds per clock cycle"); ax.grid(axis="x", visible=False)
r4 = rows[2]
axs[1][1].text(2, r4["dyn"] * 0.5, "does not fit\n100 MHz", ha="center", fontsize=8, color=SURF)
fig.suptitle("FPGA performance is more than throughput: 1 round per cycle wins almost everywhere",
             x=0.01, ha="left", fontsize=13, fontweight="bold")
fig.text(0.01, 0.925, "Top 256x256 RGB on the Artix-7 100T. Area, clock and power from Vivado place & route, "
         "cycles from simulation. Dark bar = the version on the board", fontsize=9, color=INK2)
fig.tight_layout(rect=(0, 0, 1, 0.91))
fig.savefig("graphs/7_fpga_performance.png", dpi=160); plt.close(fig); print("wrote 7_fpga_performance.png")

# ---------- where the time of one image goes on the board ----------
board = [r for r in csv.reader(open("data/board.csv"), delimiter=";") if r[7] == "True"]
t_img = min(float(r[6]) for r in board if r[0] == "2000000" and r[1] == "256" and r[3] == "1")
blocks = 256 * 256 * 3 // 16
t_enc = (blocks * int(cyc[("top", 1)]["cycles"]) / BLOCKS) * 10e-9  # at 100 MHz
t_lat = int(cyc[("top", 1)]["firstOut"]) * 10e-9
bp = open("data/board_power.txt").read()
p_total, p_dyn, p_static = (num(r"\| Total On-Chip Power \(W\)\s*\|\s*([\d.]+)", bp),
                            num(r"\| Dynamic \(W\)\s*\|\s*([\d.]+)", bp), num(r"\| Device Static \(W\)\s*\|\s*([\d.]+)", bp))

fig, ax = plt.subplots(figsize=(11, 4.2))
items = [("UART (PC <-> board)", t_img - t_enc), ("encryption on the FPGA", t_enc), ("first byte latency", t_lat)]
ys = [2, 1, 0]
ax.barh(ys, [v for _, v in items], color=[ORANGE, BLUE, BLUE], height=0.55, edgecolor=SURF, lw=2)
ax.set_xscale("log")
ax.set_yticks(ys); ax.set_yticklabels([n for n, _ in items])
for y, (_, v) in zip(ys, items):
    txt = f"{v:.3f} s" if v > 0.1 else (f"{v * 1000:.2f} ms" if v > 1e-4 else f"{v * 1e9:.0f} ns")
    share = f"  ({100 * v / t_img:.2f}% of the image time)" if v > 1e-4 else ""
    ax.text(v * 1.3, y, txt + share, va="center", fontsize=9)
ax.set_xlim(t_lat / 3, t_img * 60)
ax.set_xlabel("seconds (log scale)"); ax.grid(axis="y", visible=False)
ax.set_title("One 256x256 RGB image: the FPGA works only ~0.2% of the time", fontsize=13, pad=34)
ax.text(0, 1.03, f"2 Mbaud, 100 MHz, measured {t_img:.2f} s per image. Whole board design: {p_total:.3f} W on chip\n"
        f"({p_static:.3f} W static + {p_dyn:.3f} W dynamic, Vivado estimate) = {p_total * t_img:.2f} J per image",
        transform=ax.transAxes, fontsize=9, color=INK2, va="bottom")
fig.tight_layout()
fig.savefig("graphs/8_time_breakdown.png", dpi=160); plt.close(fig); print("wrote 8_time_breakdown.png")

lines = [f"[7] {r['k']} round(s)/cycle: {r['lut']:.0f} LUTs, {r['ff']:.0f} FFs, fmax {r['fmax']:.0f} MHz, Top {r['mbs']:.0f} MB/s at fmax, "
         f"latency {r['lat']:.0f} ns, dynamic {r['dyn']:.0f} mW at 100 MHz, "
         + (f"{r['nj']:.3f} nJ/byte" if r["nj"] else "does not fit 100 MHz") + f", {r['eff']:.0f} MB/s per 1000 LUTs" for r in rows]
lines.append(f"[8] one 256x256 image at 2 Mbaud: {t_img:.3f} s total, encryption {t_enc * 1000:.2f} ms ({100 * t_enc / t_img:.2f}%), "
             f"first byte after {t_lat * 1e9:.0f} ns; board power {p_total:.3f} W ({p_static:.3f} static + {p_dyn:.3f} dynamic), "
             f"{p_total * t_img:.2f} J per image")
old = [l for l in open("summary.txt").read().splitlines() if not l.startswith(("[7]", "[8]"))]
open("summary.txt", "w").write("\n".join(old + lines) + "\n")
print("\n".join(lines))
