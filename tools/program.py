# Programs the board with the bitstream files using vivado in batch mode,
#
#   python tools/program.py images        256x256 RGB at 2 Mbaud, for send_images.py
#   python tools/program.py video         64x64 RGB at 3 Mbaud, for video.py
#   python tools/program.py my/BoardTop.bit

import glob, os, shutil, subprocess, sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
READY = {
    "images": "bitstreams/images_256x256_2M.bit",
    "video": "bitstreams/video_64x64_3M.bit",
}


def find_vivado():
    if os.environ.get("VIVADO"):
        return os.environ["VIVADO"]
    for name in ("vivado", "vivado_lab"):
        if shutil.which(name):
            return shutil.which(name)
    found = []
    for base in (r"C:\Xilinx\Vivado", r"C:\Xilinx\Vivado_Lab", "/tools/Xilinx/Vivado", "/opt/Xilinx/Vivado"):
        found += glob.glob(os.path.join(base, "*", "bin", "vivado*.bat" if os.name == "nt" else "vivado*"))
    found = [f for f in found if os.path.basename(f).split(".")[0] in ("vivado", "vivado_lab")]
    if not found:
        sys.exit("vivado not found, install it (see the README) or set VIVADO to the full path of vivado(.bat)")
    return sorted(found)[-1]  # newest version


if len(sys.argv) != 2:
    sys.exit("usage: python tools/program.py images | video | <file.bit>")
name = sys.argv[1]
bit = os.path.join(ROOT, READY[name]) if name in READY else os.path.abspath(name)
if not os.path.isfile(bit):
    sys.exit(f"no such bitstream: {bit}\nuse images, video or the path of a .bit file")

vivado = find_vivado()
# vivado runs in the repo root, so give it a path relative to that (it can't take C:/... here)
try:
    arg = os.path.relpath(bit, ROOT)
except ValueError:  # other drive on windows
    arg = bit
print(f"programming {arg} with {vivado}, takes ~30 s")
cmd = [vivado, "-mode", "batch", "-nolog", "-nojournal", "-source", "fpga/program.tcl",
       "-tclargs", arg.replace("\\", "/")]
run = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, shell=(os.name == "nt"))
lines = (run.stdout + run.stderr).splitlines()  # vivado also echoes the script lines with a # in front
if any(l.startswith("PROGRAMMED") for l in lines):
    print("done, the board is programmed")
else:
    print("\n".join(l for l in lines if l.startswith("ERROR")) or "\n".join(lines[-30:]))
    sys.exit("programming failed: is the board connected by USB and switched on? "
             "Close the vivado hardware manager if it is open")
