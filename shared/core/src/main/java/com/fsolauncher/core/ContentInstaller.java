package com.fsolauncher.core;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.compress.archivers.sevenz.*;
import org.apache.commons.compress.archivers.zip.*;
import static com.fsolauncher.core.Models.list;

/** Builds immutable content folders, then activates the entire graph with one manifest rename. */
public final class ContentInstaller {
    private final Path root;
    private final HttpTransport transport;
    private final HttpTransport.Progress progress;
    private final Gson gson = new Gson();
    public ContentInstaller(Path root, HttpTransport transport, HttpTransport.Progress progress) {
        this.root = root.toAbsolutePath().normalize(); this.transport = transport; this.progress = progress;
    }
    public Models.State state() throws IOException {
        Path manifest = root.resolve("installed.json");
        if (!Files.exists(manifest)) return null;
        Models.State state = gson.fromJson(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8), Models.State.class);
        if (state == null || state.schema != 1) throw new IOException("Unsupported installation state");
        for (String path : state.modPaths) {
            if (!Files.isDirectory(SafeFiles.resolve(root, path))) throw new IOException("Installed game files are missing");
        }
        return state;
    }
    public boolean current(Resolver.Plan plan) throws IOException {
        Models.State state = state();
        if (state == null || !plan.rootId.equals(state.rootId) || state.mods.size() != plan.selections.size()) return false;
        for (Resolver.Selection s : plan.selections) if (reuse(state, s) == null) return false;
        return true;
    }
    private Models.Installed reuse(Models.State old, Resolver.Selection s) throws IOException {
        if (old == null) return null;
        Set<String> packages = new HashSet<>();
        for (Models.Package p : s.packages) packages.add(p.name);
        for (Models.Installed i : old.mods)
            if (s.mod.id.equals(i.id) && s.mod.version.equals(i.version) &&
                packages.equals(new HashSet<>(i.packages)) && fingerprint(s).equals(i.manifestHash) &&
                Files.isDirectory(SafeFiles.resolve(root, i.directory))) return i;
        return null;
    }
    public Models.State install(Resolver.Plan plan) throws IOException {
        Files.createDirectories(root.resolve("content"));
        Models.State old = state(), next = new Models.State();
        next.generation = UUID.randomUUID().toString(); next.rootId = plan.rootId; next.rootVersion = plan.rootVersion;
        List<Path> created = new ArrayList<>();
        int totalPackages = plan.selections.stream().mapToInt(s -> s.packages.size()).sum();
        int installedPackages = 0;
        progress.packages(0, totalPackages, null);
        boolean committed = false;
        try {
            for (Resolver.Selection selected : plan.selections) {
                SafeFiles.checkCancelled();
                Models.Installed item = reuse(old, selected);
                if (item != null) {
                    next.mods.add(item);
                    installedPackages += selected.packages.size();
                    progress.packages(installedPackages, totalPackages, null);
                    continue;
                }
                String relative = "content/" + SafeFiles.component(selected.mod.id) + "-" +
                    SafeFiles.component(selected.mod.version) + "-" + next.generation;
                Path destination = SafeFiles.resolve(root, relative);
                Files.createDirectories(destination); created.add(destination);
                item = new Models.Installed(); item.id = selected.mod.id; item.version = selected.mod.version; item.directory = relative;
                item.manifestHash = fingerprint(selected);
                for (Models.Package pkg : selected.packages) {
                    SafeFiles.checkCancelled();
                    progress.packages(installedPackages, totalPackages, selected.mod.id + " / " + pkg.name);
                    item.packages.add(pkg.name);
                    Path target = destination;
                    if (selected.mod.devMode) {
                        target = SafeFiles.resolve(destination, SafeFiles.component(pkg.folder));
                        item.searchPaths.add(root.relativize(target).toString().replace('\\','/'));
                    }
                    for (Models.Archive archive : list(pkg.files)) {
                        SafeFiles.checkCancelled();
                        Path cached = transport.archive(archive, root.resolve("downloads"));
                        extract(cached, archive, pkg, target);
                    }
                    progress.packages(++installedPackages, totalPackages, null);
                }
                if (!selected.mod.devMode) item.searchPaths.add(relative);
                SafeFiles.atomicWrite(destination.resolve(".mod.json"), gson.toJson(selected.mod));
                next.mods.add(item);
            }
            orderSearchPaths(plan, next);
            if (next.modPaths.isEmpty()) throw new IOException("The game has no content paths");
            progress.update("Finalizing installation", 0, 0);
            SafeFiles.checkCancelled();
            SafeFiles.atomicWrite(root.resolve("installed.json"), gson.toJson(next));
            committed = true;
            // Cleanup never touches user profiles, and cannot invalidate the committed graph.
            try { cleanup(next); } catch (IOException ignored) { }
            return next;
        } finally {
            if (!committed) for (Path path : created) {
                try { SafeFiles.deleteTree(path); } catch (IOException ignored) { }
            }
        }
    }
    private void orderSearchPaths(Resolver.Plan plan, Models.State state) throws IOException {
        Map<String, Resolver.Selection> mods = new LinkedHashMap<>();
        Map<String, Models.Installed> installed = new LinkedHashMap<>();
        for (Resolver.Selection s : plan.selections) mods.put(s.mod.id, s);
        for (Models.Installed i : state.mods) installed.put(i.id, i);
        LinkedHashSet<String> order = new LinkedHashSet<>();
        visit(plan.rootId, mods, installed, new HashSet<>(), order);
        for (String id : mods.keySet()) visit(id, mods, installed, new HashSet<>(), order);
        state.modPaths.addAll(order);
    }
    private void visit(String id, Map<String, Resolver.Selection> mods, Map<String, Models.Installed> installed,
                       Set<String> visiting, Set<String> order) throws IOException {
        if (!visiting.add(id)) return;
        Resolver.Selection s = mods.get(id);
        if (s == null) return;
        boolean ownAdded = false;
        for (String flag : list(s.mod.modFlag)) {
            if (flag.equals(id)) { order.addAll(installed.get(id).searchPaths); ownAdded = true; }
            else if (mods.containsKey(flag)) visit(flag, mods, installed, visiting, order);
            else throw new IOException("mod_flag references an unresolved dependency: " + flag);
        }
        if (!ownAdded) order.addAll(installed.get(id).searchPaths);
        for (Models.Package p : s.packages)
            for (Models.Dependency d : list(p.dependencies))
                if (mods.containsKey(d.id)) visit(d.id, mods, installed, visiting, order);
    }
    private void cleanup(Models.State state) throws IOException {
        Set<Path> active = new HashSet<>();
        for (Models.Installed i : state.mods) active.add(SafeFiles.resolve(root, i.directory));
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root.resolve("content"))) {
            for (Path p : dirs) if (Files.isDirectory(p) && !active.contains(p)) SafeFiles.deleteTree(p);
        }
        if (Files.isDirectory(root.resolve("downloads"))) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(root.resolve("downloads"))) {
                for (Path path : files) if (Files.isRegularFile(path)) Files.delete(path);
            }
        }
    }
    private String fingerprint(Resolver.Selection selection) throws IOException {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                (gson.toJson(selection.mod.modFlag) + gson.toJson(selection.packages)).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format("%02x", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private void extract(Path archiveFile, Models.Archive archive, Models.Package pkg, Path target) throws IOException {
        progress.update("Installing " + pkg.name, 0, 0);
        Path archiveDestination = SafeFiles.resolve(target, archive.dest == null ? "" : archive.dest);
        Map<String, Models.Member> members = new LinkedHashMap<>();
        for (Models.Member m : list(pkg.filelist)) {
            if (!Objects.equals(m.archive, archive.filename)) continue;
            String original = normal(m.originalName == null ? m.filename : m.originalName);
            SafeFiles.resolve(target, original);
            Path declaredDestination = SafeFiles.resolve(target, m.filename);
            Models.Member output = new Models.Member();
            output.filename = m.filename;
            if (!declaredDestination.startsWith(archiveDestination)) {
                // Older repositories express filename relative to the archive destination.
                output.filename = target.toAbsolutePath().normalize().relativize(
                    SafeFiles.resolve(archiveDestination, m.filename)).toString();
            }
            if (SafeFiles.executable(original) || SafeFiles.executable(m.filename))
                throw new IOException("Executable game download refused: " + m.filename);
            if (members.put(original, output) != null) throw new IOException("Duplicate archive member: " + original);
        }
        if (members.isEmpty()) throw new IOException("Missing file manifest for " + archive.filename);
        Set<String> seen = new HashSet<>();
        String name = archive.filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".7z")) {
            try (SevenZFile input = SevenZFile.builder().setFile(archiveFile.toFile()).setMaxMemoryLimitKb(262144).get()) {
                SevenZArchiveEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    SafeFiles.checkCancelled();
                    String path = normal(entry.getName());
                    SafeFiles.resolve(target, path);
                    if (entry.isDirectory()) continue;
                    if (entry.getHasWindowsAttributes() && (entry.getWindowsAttributes() & 0x400) != 0)
                        throw new IOException("Archive links are not allowed");
                    Models.Member expected = members.get(path);
                    if (expected == null) throw new IOException("Undeclared archive member: " + path);
                    final SevenZFile source = input;
                    writeMember(new InputStream() {
                        @Override public int read(byte[] b, int o, int n) throws IOException { return source.read(b,o,n); }
                        @Override public int read() throws IOException { return source.read(); }
                    }, entry.getSize(), expected, target, seen, path);
                }
            }
        } else if (name.endsWith(".zip")) {
            try (ZipFile input = ZipFile.builder().setFile(archiveFile.toFile()).get()) {
                Enumeration<ZipArchiveEntry> entries = input.getEntries();
                while (entries.hasMoreElements()) {
                    SafeFiles.checkCancelled();
                    ZipArchiveEntry entry = entries.nextElement(); String path = normal(entry.getName());
                    SafeFiles.resolve(target, path);
                    if (entry.isUnixSymlink()) throw new IOException("Archive links are not allowed");
                    if (entry.isDirectory()) continue;
                    Models.Member expected = members.get(path);
                    if (expected == null) throw new IOException("Undeclared archive member: " + path);
                    try (InputStream in = input.getInputStream(entry)) {
                        writeMember(in, entry.getSize(), expected, target, seen, path);
                    }
                }
            }
        } else throw new IOException("Unsupported game archive: " + archive.filename + " (supported: 7z, zip)");
        if (seen.size() != members.size()) throw new IOException("Archive is missing declared game files");
    }
    private static String normal(String name) { return name.replace('\\','/').replaceFirst("^\\./", ""); }
    private void writeMember(InputStream in, long size, Models.Member expected, Path target,
                             Set<String> seen, String original) throws IOException {
        if (!seen.add(original)) throw new IOException("Duplicate archive entry: " + original);
        if (size < 0 || size > 32L * 1024 * 1024 * 1024) throw new IOException("Unreasonable game file size");
        Path out = SafeFiles.resolve(target, expected.filename);
        if (Files.exists(out)) throw new IOException("Conflicting package file: " + expected.filename);
        Files.createDirectories(out.getParent());
        long written = 0;
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(out))) {
            byte[] buffer = new byte[131072]; int n;
            while ((n = in.read(buffer)) != -1) {
                SafeFiles.checkCancelled(); written += n;
                if (written > size) throw new IOException("File exceeds archive metadata");
                file.write(buffer,0,n);
            }
        }
        if (written != size) throw new IOException("Truncated archive member");
        // Rely on the verified archive and extractor checks without rereading each output file.
        progress.update("Installed " + expected.filename, 0, 0);
    }
}
