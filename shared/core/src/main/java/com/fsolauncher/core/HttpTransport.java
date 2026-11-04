package com.fsolauncher.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** HTTPS-only transport with mirror fallback, bounded metadata and hash-addressed resumable archives. */
public final class HttpTransport {
    public interface Progress {
        void update(String message, long completed, long total);
        default void packages(int installed, int total, String currentPackage) {}
    }
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    private final Progress progress;
    private final ConnectionFactory factory;
    public HttpTransport(Progress progress) { this(progress, url -> (HttpURLConnection)url.openConnection()); }
    HttpTransport(Progress progress, ConnectionFactory factory) { this.progress = progress; this.factory = factory; }
    private HttpURLConnection connection(String location) throws IOException {
        URI uri;
        try { uri = URI.create(location); } catch (IllegalArgumentException e) { throw new IOException("Invalid download URL", e); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw new IOException("Only HTTPS downloads are allowed");
        HttpURLConnection c = factory.open(uri.toURL());
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000); c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "FSO-Android-TC-Launcher/1.0");
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }
    private HttpURLConnection open(String url, long offset, String etag) throws IOException {
        for (int redirect = 0; redirect < 6; redirect++) {
            SafeFiles.checkCancelled();
            HttpURLConnection c = connection(url);
            if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");
            if (etag != null) c.setRequestProperty("If-None-Match", etag);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String next = c.getHeaderField("Location"); c.disconnect();
                if (next == null) throw new IOException("Redirect without location");
                url = URI.create(url).resolve(next).toString(); continue;
            }
            return c;
        }
        throw new IOException("Too many redirects");
    }
    public String json(List<String> urls, Path cache, boolean refresh) throws IOException {
        IOException last = null;
        for (String url : urls) {
            HttpURLConnection c = null;
            try {
                Path etagFile = cache.resolveSibling(cache.getFileName() + ".etag");
                String etag = refresh && Files.exists(cache) && Files.exists(etagFile) ?
                    new String(Files.readAllBytes(etagFile), StandardCharsets.UTF_8) : null;
                if (!refresh && Files.exists(cache)) return new String(Files.readAllBytes(cache), StandardCharsets.UTF_8);
                c = open(url, 0, etag);
                if (c.getResponseCode() == 304 && Files.exists(cache))
                    return new String(Files.readAllBytes(cache), StandardCharsets.UTF_8);
                if (c.getResponseCode() != 200) throw new IOException("Metadata server returned " + c.getResponseCode());
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        SafeFiles.checkCancelled();
                        if (out.size() + n > 64 * 1024 * 1024) throw new IOException("Metadata is too large");
                        out.write(buffer, 0, n);
                    }
                }
                String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                com.google.gson.JsonParser.parseString(text);
                SafeFiles.atomicWrite(cache, text);
                String newEtag = c.getHeaderField("ETag");
                if (newEtag != null) SafeFiles.atomicWrite(etagFile, newEtag);
                else Files.deleteIfExists(etagFile);
                return text;
            } catch (IOException | RuntimeException e) {
                if (e instanceof java.util.concurrent.CancellationException) throw e;
                last = new IOException("Cannot fetch metadata from " + url, e);
            } finally { if (c != null) c.disconnect(); }
        }
        throw last == null ? new IOException("No metadata mirrors configured") : last;
    }
    public Path archive(Models.Archive archive, Path cache) throws IOException {
        Files.createDirectories(cache);
        String hash = SafeFiles.hash(archive.checksum);
        Path complete = cache.resolve(hash + ".archive"), partial = cache.resolve(hash + ".part");
        if (Files.exists(complete)) {
            try { SafeFiles.verify(complete, archive.checksum); return complete; }
            catch (IOException e) { Files.delete(complete); }
        }
        if (archive.filesize <= 0) throw new IOException("Invalid archive size");
        IOException last = null;
        for (String url : Models.list(archive.urls)) {
            HttpURLConnection c = null;
            try {
                long offset = Files.exists(partial) ? Files.size(partial) : 0;
                if (offset > archive.filesize) { Files.delete(partial); offset = 0; }
                if (offset < archive.filesize) {
                    c = open(url, offset, null);
                    int code = c.getResponseCode();
                    if (code == 200) offset = 0;
                    else if (code == 206) {
                        Matcher m = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(
                            String.valueOf(c.getHeaderField("Content-Range")));
                        if (!m.matches() || Long.parseLong(m.group(1)) != offset ||
                            Long.parseLong(m.group(3)) != archive.filesize) throw new IOException("Invalid resume response");
                    } else throw new IOException("Download server returned " + code);
                    try (InputStream in = c.getInputStream();
                         OutputStream out = new BufferedOutputStream(new FileOutputStream(partial.toFile(), offset > 0))) {
                        byte[] buffer = new byte[131072]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            SafeFiles.checkCancelled(); offset += n;
                            if (offset > archive.filesize) throw new IOException("Archive exceeds declared size");
                            out.write(buffer, 0, n);
                            progress.update("Downloading " + archive.filename, offset, archive.filesize);
                        }
                    }
                }
                if (Files.size(partial) != archive.filesize) throw new IOException("Incomplete archive: " + archive.filename);
                progress.update("Verifying " + archive.filename, 0, 0);
                try { SafeFiles.verify(partial, archive.checksum); }
                catch (IOException mismatch) { Files.deleteIfExists(partial); throw mismatch; }
                Files.move(partial, complete, StandardCopyOption.REPLACE_EXISTING);
                return complete;
            } catch (IOException e) { last = e; }
            finally { if (c != null) c.disconnect(); }
        }
        throw last == null ? new IOException("No download URLs for " + archive.filename) : last;
    }
}
