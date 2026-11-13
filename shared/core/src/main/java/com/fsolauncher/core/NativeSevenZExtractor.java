package com.fsolauncher.core;

import java.io.IOException;
import java.nio.file.Path;

/** Official 7-Zip decoder, including parallel decoding of independent LZMA2 chunks. */
public final class NativeSevenZExtractor implements SevenZExtractor {
    private static final class Library {
        static { System.loadLibrary("archive7z"); }
        static void load() { }
    }
    private final int threads;
    private final long memoryBytes;

    public NativeSevenZExtractor(int threads, long memoryBytes) {
        this.threads = Math.max(1, Math.min(4, threads));
        this.memoryBytes = Math.max(64L << 20, Math.min(1L << 30, memoryBytes));
    }

    @Override public void extract(Path archive, Sink sink) throws IOException {
        sink.checkCancelled();
        Library.load();
        extractNative(archive.toAbsolutePath().toString(), sink, threads, memoryBytes);
    }

    private static native void extractNative(String archive, Sink sink, int threads, long memoryBytes) throws IOException;
}
