"""Validate Android ELF alignment: 16 KB for 64-bit libraries, 4 KB for 32-bit."""
import argparse
import struct
import sys
import zipfile
from pathlib import Path

def check(name, data):
    if data[:4] != b"\x7fELF":
        return [f"{name}: not ELF"]
    endian = "<" if data[5] == 1 else ">"
    is64 = data[4] == 2
    # Android's 16 KB requirement applies to arm64-v8a and x86_64 libraries.
    # https://developer.android.com/guide/practices/page-sizes
    page_size = 16384 if is64 else 4096
    if is64:
        off = struct.unpack_from(endian + "Q", data, 32)[0]
        size, count = struct.unpack_from(endian + "HH", data, 54)
    else:
        off = struct.unpack_from(endian + "I", data, 28)[0]
        size, count = struct.unpack_from(endian + "HH", data, 42)
    failures = []
    for i in range(count):
        row = off + size * i
        kind = struct.unpack_from(endian + "I", data, row)[0]
        if kind != 1:
            continue
        if is64:
            offset, address = struct.unpack_from(endian + "QQ", data, row + 8)
            alignment = struct.unpack_from(endian + "Q", data, row + 48)[0]
        else:
            offset, address = struct.unpack_from(endian + "II", data, row + 4)
            alignment = struct.unpack_from(endian + "I", data, row + 28)[0]
        if alignment < page_size or (address - offset) % page_size:
            failures.append(f"{name}: LOAD segment alignment={alignment}, offset={offset}, required={page_size}")
    return failures

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    errors, count = [], 0
    if args.path.suffix == ".apk":
        with zipfile.ZipFile(args.path) as apk:
            for item in apk.infolist():
                if item.filename.endswith(".so"):
                    errors += check(item.filename, apk.read(item)); count += 1
    else:
        for lib in args.path.rglob("*.so"):
            errors += check(str(lib), lib.read_bytes()); count += 1
    print(f"Checked {count} native libraries; {len(errors)} incompatible load segments.")
    print("\n".join(errors))
    sys.exit(1 if errors or not count else 0)
