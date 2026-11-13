# 7-Zip extraction subset

Unmodified upstream source from 7-Zip 26.06 by Igor Pavlov:
https://github.com/ip7z/7zip/tree/d3296e39e1f1f92c7b41c0c3848e65640428d42d

Imported by `scripts/vendor-sevenzip.py` from the pinned, SHA-256-verified source
archive. The script follows quoted includes from the source list in
`shared/src/main/cpp/sevenzip/CMakeLists.txt`; no downloads occur during builds.

The extraction-only shared library includes LZMA, LZMA2, Copy, BCJ/BCJ2, Delta,
BZip2, Deflate and PPMd decoding, including the upstream LZMA2 worker pool.
Compression, encryption, external codec loading and RAR code are excluded.
Sources retain their upstream licenses. See `DOC/License.txt` and
`DOC/copying.txt` (LGPL 2.1 or later; some files are public domain).

The JNI adapter and build configuration are outside this directory. The
library is dynamically linked and can be rebuilt from this repository with
the Android NDK/CMake versions specified in `shared/version.gradle`.
