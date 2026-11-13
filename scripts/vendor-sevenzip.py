"""Import the unmodified extraction subset from a verified, pinned upstream zip.

Download https://codeload.github.com/ip7z/7zip/zip/d3296e39e1f1f92c7b41c0c3848e65640428d42d
and run: python scripts/vendor-sevenzip.py path/to/7zip.zip
"""
import hashlib
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
COMMIT = "d3296e39e1f1f92c7b41c0c3848e65640428d42d"
SHA256 = "30694fe4a29beea007394a0095290c654dbbcb36da1c8252ea1411efd812a749"


def vendor(archive):
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise ValueError("7-Zip source archive checksum mismatch")
    cmake = (ROOT / "shared/src/main/cpp/sevenzip/CMakeLists.txt").read_text()
    pending = re.findall(r"(?:C|CPP)/[\w/]+\.(?:cpp|c)\b", cmake)
    pending += ["DOC/License.txt", "DOC/copying.txt", "CPP/Common/MyInitGuid.h"]
    target = ROOT / "shared/src/main/cpp/third_party/7zip"
    visited = set()
    with zipfile.ZipFile(archive) as source:
        prefix = "7zip-" + COMMIT + "/"
        names = set(source.namelist())
        while pending:
            name = pending.pop()
            if name in visited:
                continue
            visited.add(name)
            data = source.read(prefix + name)
            path = target / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            for include in re.findall(rb'^\s*#\s*include\s*"([^"]+)"', data, re.M):
                # Resolve quoted includes, including the local precompiled header.
                parts = []
                for part in (PurePosixPath(name).parent / include.decode()).parts:
                    if part == "..":
                        parts.pop()
                    elif part != ".":
                        parts.append(part)
                dependency = "/".join(parts)
                if prefix + dependency in names:
                    pending.append(dependency)
    print(f"Vendored {len(visited)} unmodified 7-Zip files")


if __name__ == "__main__":
    vendor(Path(sys.argv[1]))
