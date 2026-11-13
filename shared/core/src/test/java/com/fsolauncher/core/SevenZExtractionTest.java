package com.fsolauncher.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.apache.commons.compress.archivers.sevenz.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SevenZExtractionTest {
    static SevenZExtractor extractor() {
        return Boolean.getBoolean("launcher.native7z") ? new NativeSevenZExtractor(4, 256L << 20) : SevenZExtractor.JAVA;
    }
    private static final class Sink implements SevenZExtractor.Sink {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        ByteArrayOutputStream current;
        String name;
        long expected;
        boolean cancel, cancelDuringWrite, observeThreads;
        int peakThreads;
        public OutputStream open(String name, long size, boolean directory, boolean link) throws IOException {
            if (link) throw new IOException("link");
            if (directory) return null;
            this.name = name; expected = size;
            return current = new ByteArrayOutputStream() {
                @Override public synchronized void write(byte[] bytes, int offset, int length) {
                    super.write(bytes, offset, length);
                    if (observeThreads) peakThreads = Math.max(peakThreads, threadCount());
                    if (cancelDuringWrite) cancel = true;
                }
            };
        }
        public void complete() {
            if (current != null) {
                assertEquals(expected, current.size());
                files.put(name, current.toByteArray()); current = null;
            }
        }
        public void checkCancelled() { if (cancel) throw new CancellationException("paused"); }
    }
    private static int threadCount() {
        String[] threads = new File("/proc/self/task").list();
        return threads == null ? 0 : threads.length;
    }
    @Test public void decodesLzmaAndLzma2AndEmptyUnicodeEntries() throws Exception {
        for (SevenZMethod method : new SevenZMethod[]{SevenZMethod.LZMA, SevenZMethod.LZMA2}) {
            Path archive = Files.createTempFile("decoder", ".7z");
            try {
                byte[] data = new byte[65536]; new Random(17).nextBytes(data);
                String name = "data/日本語-🚀.tbl";
                try (SevenZOutputFile output = new SevenZOutputFile(archive.toFile())) {
                    output.setContentCompression(method);
                    SevenZArchiveEntry directory = new SevenZArchiveEntry(); directory.setName("empty"); directory.setDirectory(true);
                    output.putArchiveEntry(directory); output.closeArchiveEntry();
                    for (String path : new String[]{name, "zero.tbl"}) {
                        SevenZArchiveEntry entry = new SevenZArchiveEntry(); entry.setName(path);
                        output.putArchiveEntry(entry);
                        if (path.equals(name)) output.write(data);
                        output.closeArchiveEntry();
                    }
                }
                Sink sink = new Sink(); extractor().extract(archive, sink);
                assertArrayEquals(data, sink.files.get(name));
                assertArrayEquals(new byte[0], sink.files.get("zero.tbl"));
            } finally { Files.delete(archive); }
        }
    }
    @Test public void rejectsBadSignatureAndPropagatesCancellation() throws Exception {
        Path archive = Files.createTempFile("decoder-bad", ".7z");
        try {
            Files.write(archive, new byte[64]);
            try { extractor().extract(archive, new Sink()); fail("Bad archive accepted"); } catch (IOException expected) { }
            Sink cancelled = new Sink(); cancelled.cancel = true;
            try { extractor().extract(archive, cancelled); fail("Cancellation ignored"); } catch (CancellationException expected) { }
        } finally { Files.delete(archive); }
    }
    @Test public void decodesSolidLzma2WithIndependentChunks() throws Exception {
        Path archive = Files.createTempFile("decoder-solid", ".7z");
        try (InputStream input = getClass().getResourceAsStream("/fixtures/solid-lzma2.7z")) {
            assertNotNull(input); Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING);
            Sink sink = new Sink();
            int initialThreads = threadCount();
            sink.observeThreads = Boolean.getBoolean("launcher.native7z") && initialThreads != 0;
            extractor().extract(archive, sink);
            if (sink.observeThreads) {
                System.out.println("Solid LZMA2: " + (sink.peakThreads - initialThreads) + " additional threads during extraction");
                assertTrue("No worker threads observed", sink.peakThreads > initialThreads);
            }
            assertEquals(2, sink.files.size());
            for (int i = 0; i < 2; i++) {
                byte[] bytes = sink.files.get("chunk" + i + ".tbl");
                assertNotNull(bytes); assertEquals(8 << 20, bytes.length);
                // Fixture bytes repeat 0..255, with a distinct offset per file.
                for (int j = 0; j < bytes.length; j++) assertEquals((byte)(j + i), bytes[j]);
            }
            Sink cancelled = new Sink(); cancelled.cancelDuringWrite = true;
            try { extractor().extract(archive, cancelled); fail("Extraction did not pause"); }
            catch (CancellationException expected) { assertTrue(cancelled.files.isEmpty()); }
        } finally { Files.delete(archive); }
    }
    /** Run these tests and the complete installer tests in Android's VM, without installing an app. */
    public static void main(String[] args) {
        System.setProperty("launcher.native7z", "true");
        org.junit.runner.Result result = org.junit.runner.JUnitCore.runClasses(CoreTest.class, SevenZExtractionTest.class);
        for (org.junit.runner.notification.Failure failure : result.getFailures()) {
            System.err.println(failure.toString()); System.err.println(failure.getTrace());
        }
        System.out.println("Native 7z: " + result.getRunCount() + " tests, " + result.getFailureCount() + " failures");
        if (!result.wasSuccessful()) System.exit(1);
    }
}
