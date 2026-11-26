# graph 9: the ascon core alone, without the byte interface. Is it better to spend the LUTs on more
# rounds per cycle or on more cores? run after make_graphs.py, uses data/core and cycles.csv
import csv, os, re
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

os.chdir(os.path.dirname(os.path.abspath(__file__)))
BLUE, LIGHT = "#2a78d6", "#a9c8ee"
INK, INK2, GRID, SURF = "#0b0b0b", "#52514e", "#e4e3df", "#fcfcfb"
plt.rcParams.update({
    "figure.facecolor": SURF, "axes.facecolor": SURF, "savefig.facecolor": SURF,
    "axes.edgecolor": GRID, "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK2,
    "text.color": INK, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.8, "axes.axisbelow": True,
    "axes.spines.top": False, "axes.spines.right": False, "font.size": 10,
    "axes.titlesize": 11, "axes.titleweight": "bold", "axes.titlelocation": "left",
})
TOTAL_LUT, BUDGET = 63400, 0.8
BLOCKS = 48  # blocks in the simulation of cycles.csv


def num(pattern, text):
    return float(re.search(pattern, text).group(1))


cyc = {(r["part"], int(r["roundsPerCycle"])): r for r in csv.DictReader(open("data/cycles.csv"), delimiter=";")}
rows = []
for k in (1, 2, 4):
    util, timing, power = (open(f"data/core/k{k}/{n}.txt").read() for n in ("util", "timing", "power"))
    lut = num(r"\| Slice LUTs\s*\|\s*(\d+)", util)
    wns = num(r"WNS\(ns\).*?\n.*?\n\s*(-?\d+\.\d+)", timing)
    fmax = 1000 / (10 - wns)
    dyn = num(r"\| Dynamic \(W\)\s*\|\s*([\d.]+)", power)  # at 100 MHz
    cpb = int(cyc[("core", k)]["cycles"]) / BLOCKS  # cycles per 16 byte block
    mbs = 16 / cpb * fmax  # MB/s at its own max clock
    cores = int(BUDGET * TOTAL_LUT // lut)
    rows.append(dict(k=k, lut=lut, wns=wns, fmax=fmax, cpb=cpb, mbs=mbs, eff=mbs / lut * 1000,
                     cores=cores, total=cores * mbs / 1000,  # GB/s with the FPGA filled to 80%
                     nj=dyn / 1e8 * cpb / 16 * 1e9))  # energy per byte: power at 100 MHz x time per byte

ks = [str(r["k"]) for r in rows]
panels = [
    ("cpb", "Cycles per block (lower is better)", "clock cycles per 16 bytes", "{:.1f}"),
    ("fmax", "Max clock (higher is better)", "MHz", "{:.0f}"),
    ("mbs", "Throughput of one core (higher is better)", "MB/s at its max clock", "{:.0f}"),
    ("lut", "Area of one core (lower is better)", "LUTs", "{:.0f}"),
    ("eff", "Throughput per area (higher is better)", "MB/s per 1000 LUTs", "{:.0f}"),
    ("cores", f"Cores that fit in {BUDGET:.0%} of the LUTs", "cores", "{:.0f}"),
    ("total", "All those cores together (higher is better)", "GB/s", "{:.1f}"),
    ("nj", "Energy per byte (lower is better)", "nJ per byte (Vivado power estimate)", "{:.2f}"),
]
lower = {"cpb", "lut", "nj"}
fig, axs = plt.subplots(2, 4, figsize=(16, 7.8))
for ax, (key, title, unit, fmt) in zip(axs.flat, panels):
    vals = [r[key] for r in rows]
    best = (min if key in lower else max)(range(len(rows)), key=lambda i: vals[i])
    cols = [BLUE if i == best else LIGHT for i in range(len(rows))]
    ax.bar(ks, vals, color=cols, width=0.55, edgecolor=SURF, lw=2)
    for i, (v, r) in enumerate(zip(vals, rows)):
        label = fmt.format(v) + (f"\n{r['cores']} cores" if key == "total" else "")
        ax.text(i, v, label, ha="center", va="bottom", fontsize=9)
    ax.set_ylim(0, max(vals) * 1.3)
    ax.set_title(title, fontsize=10); ax.set_ylabel(unit); ax.set_xlabel("rounds per clock cycle")
    ax.grid(axis="x", visible=False)
fig.suptitle("Ascon core alone: 1 and 2 rounds per cycle tie on total throughput, 1 round uses less energy, 4 rounds wastes the chip",
             x=0.01, ha="left", fontsize=13, fontweight="bold")
fig.text(0.01, 0.9, "Core only (no byte interface), Artix-7 100T, Vivado place & route + simulated cycles. "
         f"Dark bar = best in each panel. 4 rounds/cycle does not reach 100 MHz (slack {rows[2]['wns']:.1f} ns).\n"
         "The total assumes the data could reach every core, which the UART can not do",
         fontsize=9, color=INK2)
fig.tight_layout(rect=(0, 0, 1, 0.87))
fig.savefig("graphs/9_core_alone.png", dpi=160); plt.close(fig); print("wrote 9_core_alone.png")

lines = [f"[9] core alone, {r['k']} round(s)/cycle: {r['lut']:.0f} LUTs, {r['cpb']:.1f} cycles/block, fmax {r['fmax']:.0f} MHz "
         f"(slack {r['wns']:.3f} ns), {r['mbs']:.0f} MB/s per core, {r['eff']:.0f} MB/s per 1000 LUTs, "
         f"{r['nj']:.3f} nJ/byte, {r['cores']} cores in {BUDGET:.0%} of the LUTs = {r['total']:.1f} GB/s" for r in rows]
old = [l for l in open("summary.txt").read().splitlines() if not l.startswith("[9]")]
open("summary.txt", "w").write("\n".join(old + lines) + "\n")
print("\n".join(lines))
