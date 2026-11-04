"""Check ABI-specific ELF alignment without mistaking 32-bit libraries for 64-bit."""
import importlib.util
import struct
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "native_pages", Path(__file__).resolve().parents[1] / "check-native-pages.py")
pages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pages)


def elf(is64, alignment, offset=0, address=0):
    header_size, row_size = (64, 56) if is64 else (52, 32)
    data = bytearray(header_size + row_size)
    data[:4] = b"\x7fELF"
    data[4:6] = bytes((2 if is64 else 1, 1))
    if is64:
        struct.pack_into("<Q", data, 32, header_size)
        struct.pack_into("<HH", data, 54, row_size, 1)
        struct.pack_into("<I", data, header_size, 1)
        struct.pack_into("<QQ", data, header_size + 8, offset, address)
        struct.pack_into("<Q", data, header_size + 48, alignment)
    else:
        struct.pack_into("<I", data, 28, header_size)
        struct.pack_into("<HH", data, 42, row_size, 1)
        struct.pack_into("<I", data, header_size, 1)
        struct.pack_into("<II", data, header_size + 4, offset, address)
        struct.pack_into("<I", data, header_size + 28, alignment)
    return bytes(data)


class NativePagesTest(unittest.TestCase):
    def test_64_bit_accepts_16_kb_alignment(self):
        self.assertEqual([], pages.check("arm64/lib.so", elf(True, 16384)))

    def test_64_bit_rejects_4_kb_alignment(self):
        self.assertTrue(pages.check("x86_64/lib.so", elf(True, 4096)))

    def test_32_bit_accepts_4_kb_alignment(self):
        self.assertEqual([], pages.check("armeabi-v7a/lib.so", elf(False, 4096)))

    def test_alignment_also_requires_congruent_load_addresses(self):
        self.assertTrue(pages.check("x86_64/lib.so", elf(True, 16384, address=4096)))
        self.assertTrue(pages.check("x86/lib.so", elf(False, 4096, address=1024)))


if __name__ == "__main__":
    unittest.main()
