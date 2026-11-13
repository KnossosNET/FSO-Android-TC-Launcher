package com.fsolauncher.core;

import java.io.*;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.sevenz.*;

/** Decodes an archive; the installer owns all path validation and output streams. */
public interface SevenZExtractor {
    interface Sink {
        OutputStream open(String name, long size, boolean directory, boolean link) throws IOException;
        void complete() throws IOException;
        void checkCancelled();
    }

    void extract(Path archive, Sink sink) throws IOException;

    /** Portable implementation for JVM tools and tests. Android supplies native 7-Zip. */
    SevenZExtractor JAVA = (archive, sink) -> {
        sink.checkCancelled();
        try (SevenZFile input = SevenZFile.builder().setFile(archive.toFile()).setMaxMemoryLimitKb(262144).get()) {
            byte[] buffer = new byte[131072];
            SevenZArchiveEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                sink.checkCancelled();
                int attributes = entry.getHasWindowsAttributes() ? entry.getWindowsAttributes() : 0;
                boolean link = (attributes & 0x400) != 0 || ((attributes >>> 16) & 0170000) == 0120000;
                OutputStream output = sink.open(entry.getName(), entry.getSize(), entry.isDirectory(), link);
                if (output == null) continue;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    sink.checkCancelled();
                    output.write(buffer, 0, n);
                }
                sink.complete();
            }
        }
    };
}
