#!/usr/bin/env python3
"""Generates the launcher icons of the app.

The script writes the PNG files of the different densities without any
external library, so that it also works on a machine that has nothing but
Python installed.
"""
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

DENSITIES = {
    "mipmap-mdpi-v4": 48,
    "mipmap-hdpi-v4": 72,
    "mipmap-xhdpi-v4": 96,
    "mipmap-xxhdpi-v4": 144,
    "mipmap-xxxhdpi-v4": 192,
}

BACKGROUND = (11, 16, 32, 255)
PANEL = (27, 42, 87, 255)
ACCENT = (79, 134, 255, 255)
LIGHT = (156, 192, 255, 255)


def rounded(x, y, size, radius):
    """True when the point is outside of the rounded rectangle."""
    left = radius
    right = size - radius
    top = radius
    bottom = size - radius
    if left <= x < right or top <= y < bottom:
        return False
    cx = left if x < left else right - 1
    cy = top if y < top else bottom - 1
    return (x - cx) ** 2 + (y - cy) ** 2 > radius ** 2


def pixel(x, y, size):
    """The colour of a pixel of the icon."""
    scale = size / 108.0
    fx = x / scale
    fy = y / scale
    if rounded(fx, fy, 108, 26):
        return (0, 0, 0, 0)
    # The screen of the desktop.
    if 22 <= fx <= 86 and 34 <= fy <= 74:
        # The "L" of Linux.
        if 33 <= fx <= 38 and 44 <= fy <= 63:
            return ACCENT
        if 33 <= fx <= 48 and 58 <= fy <= 63:
            return ACCENT
        # The cursor of the mouse.
        if 70 <= fx <= 75 and 44 <= fy <= 64:
            return LIGHT
        return PANEL
    return BACKGROUND


def write_png(path, size):
    raw = bytearray()
    for y in range(size):
        raw.append(0)  # the filter type of the line
        for x in range(size):
            raw.extend(pixel(x, y, size))
    compressed = zlib.compress(bytes(raw), 9)

    def chunk(kind, data):
        out = struct.pack(">I", len(data)) + kind + data
        return out + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
    png += chunk(b"IDAT", compressed) + chunk(b"IEND", b"")
    with open(path, "wb") as handle:
        handle.write(png)
    return len(png)


def main():
    for folder, size in DENSITIES.items():
        directory = os.path.join(RES, folder)
        os.makedirs(directory, exist_ok=True)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            path = os.path.join(directory, name)
            written = write_png(path, size)
            print("%s (%d bytes)" % (path, written))


if __name__ == "__main__":
    main()
