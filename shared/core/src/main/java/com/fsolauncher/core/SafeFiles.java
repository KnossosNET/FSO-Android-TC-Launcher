package com.fsolauncher.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;

public final class SafeFiles {
    private SafeFiles() {}
    public static Path resolve(Path root, String relative) throws IOException {
        if (relative == null || relative.indexOf('\0') >= 0) throw new IOException("Invalid path");
        String r = relative.replace('\\', '/');
        if (r.startsWith("/") || r.contains(":")) throw new IOException("Absolute path refused: " + relative);
        Path base = root.toAbsolutePath().normalize(), result = base.resolve(r).normalize();
        if (!result.startsWith(base)) throw new IOException("Path leaves game folder: " + relative);
        return result;
    }
    public static String component(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_.+-]+") || value.equals(".") || value.equals(".."))
            throw new IOException("Unsafe identifier: " + value);
        return value;
    }
    public static boolean executable(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(so(?:\\.\\d+)*|dex|jar|apk|aab|exe|dll|com|bat|cmd|sh)$");
    }
    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Download paused");
    }
    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[131072]; int n;
                while ((n = in.read(buffer)) >= 0) { checkCancelled(); digest.update(buffer, 0, n); }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static String hash(List<String> checksum) throws IOException {
        if (checksum == null || checksum.size() != 2 || !"sha256".equalsIgnoreCase(checksum.get(0))
            || !checksum.get(1).matches("[a-fA-F0-9]{64}")) throw new IOException("A SHA-256 checksum is required");
        return checksum.get(1).toLowerCase(Locale.ROOT);
    }
    public static void verify(Path file, List<String> checksum) throws IOException {
        if (!sha256(file).equals(hash(checksum))) throw new IOException("Checksum mismatch: " + file.getFileName());
    }
    public static void atomicWrite(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.getFD().sync();
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    public static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
            for (Path p : (Iterable<Path>)stream.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
}
