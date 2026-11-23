# finds the serial port of the board, the USB-UART of the Nexys A7 is an FTDI chip (vendor id 0x0403)
# if there are several FTDI ports the last one is used
import sys


def find_port():
    from serial.tools import list_ports

    ftdi = [p for p in list_ports.comports() if p.vid == 0x0403]
    if not ftdi:
        names = ", ".join(p.device for p in list_ports.comports()) or "none"
        sys.exit(f"no Nexys A7 found (serial ports: {names}). Is the USB cable in and the board on? "
                 "Or give the port with --port")
    return sorted(ftdi, key=lambda p: (len(p.device), p.device))[-1].device


if __name__ == "__main__":
    print(find_port())
