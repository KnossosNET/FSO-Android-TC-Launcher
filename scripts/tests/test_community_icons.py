"""Check vector paths with Android's numeric parameter rules, rather than SVG's.

SVG allows adjacent single-digit arc flags (00/01). Android reads these as one
number, so otherwise valid SVGs can compile and then crash during inflation.
"""
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
COMMAND = re.compile(r'([AaCcHhLlMmQqSsTtVvZz])([^AaCcHhLlMmQqSsTtVvZz]*)')
NUMBER = re.compile(r'[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')
ARITY = dict(M=2, L=2, H=1, V=1, C=6, S=4, Q=4, T=2, A=7, Z=0)


def validate_android_path(path):
    segments = list(COMMAND.finditer(path))
    if not segments or segments[0].group(1).upper() != 'M':
        raise ValueError('Path must start with a move command')
    for segment in segments:
        command, parameters = segment.groups()
        values = NUMBER.findall(parameters)
        if NUMBER.sub('', parameters).strip(' ,\t\r\n'):
            raise ValueError(f'Invalid numbers in {command}')
        arity = ARITY[command.upper()]
        if (arity == 0 and values) or (arity and (not values or len(values) % arity)):
            raise ValueError(f'{command}: {len(values)} parameters; expected a multiple of {arity}')
        if command.upper() == 'A':
            for offset in range(0, len(values), arity):
                if any(float(values[offset + index]) not in (0, 1) for index in (3, 4)):
                    raise ValueError('Arc flags must be zero or one')


class CommunityIconsTest(unittest.TestCase):
    def test_rejects_svg_compact_arc_flags_that_crash_android(self):
        with self.assertRaisesRegex(ValueError, 'multiple of 7'):
            validate_android_path('M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152')

    def test_accepts_explicit_arc_flags(self):
        validate_android_path('M 20.317 4.3698 a 19.7913 19.7913 0 0 0 -4.8851 -1.5152 Z')

    def test_all_shipped_community_vector_paths_are_android_compatible(self):
        icons = list((ROOT / 'shared/src/main/res/drawable').glob('ic_community_*.xml'))
        self.assertGreaterEqual(len(icons), 5)
        for icon in icons:
            with self.subTest(icon=icon.name):
                paths = ET.parse(icon).getroot().findall('.//path')
                self.assertTrue(paths)
                for path in paths:
                    validate_android_path(path.attrib['{http://schemas.android.com/apk/res/android}pathData'])


if __name__ == '__main__':
    unittest.main()
