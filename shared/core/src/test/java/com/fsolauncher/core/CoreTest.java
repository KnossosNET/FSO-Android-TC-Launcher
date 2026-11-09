package com.fsolauncher.core;

import com.google.gson.Gson;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.fsolauncher.core.Models.*;
import org.apache.commons.compress.archivers.sevenz.*;

public class CoreTest {
    private static final Gson GSON = new Gson();
    private static final HttpTransport.Progress NOOP = (m,d,t) -> {};
    @Test public void semverPrecedenceAndNebulaRanges() {
        assertTrue(Versions.compare("1.10.2","1.9.99") > 0);
        assertTrue(Versions.compare("26.1.0","26.1.0-20260723") > 0);
        assertTrue(Versions.compare("1.0.0-beta.10","1.0.0-beta.2") > 0);
        assertEquals(0,Versions.compare("1.2.3+build1","1.2.3+build2"));
        assertTrue(Versions.matches("24.3.0-20250120",">=24.3.0-20250119"));
        assertTrue(Versions.matches("1.4.2",">=1.1.0 <2.0.0"));
        assertFalse(Versions.matches("2.0.0","^1.1.1"));
        assertTrue(Versions.matches("0.2.7","^0.2.3"));
        assertFalse(Versions.matches("0.3.0","^0.2.3"));
        assertTrue(Versions.matches("1.2.9","~1.2.0"));
        assertTrue(Versions.matches("1.10.2","1.10.x || >=2.1.0"));
        assertFalse(Versions.matches("1.11.0","1.10.x"));
    }
    @Test public void environmentFiltersByPlatformAndAbi() throws Exception {
        assertTrue(Environment.matches("android && (ARM64 || X86_64)", "arm64-v8a"));
        assertFalse(Environment.matches("windows && X86_64","x86_64"));
        assertTrue(Environment.matches("!windows && android","arm64-v8a"));
    }
    @Test public void pathTraversalAndExecutableCodeAreRejected() throws Exception {
        Path dir = Files.createTempDirectory("safe-path");
        for (String path : Arrays.asList("../escape","/absolute","C:\\escape","..\\escape")) {
            try { SafeFiles.resolve(dir,path); fail(path); } catch (IOException expected) { }
        }
        assertTrue(SafeFiles.executable("nested/libengine.so.1"));
        assertTrue(SafeFiles.executable("engine.dex"));
        assertFalse(SafeFiles.executable("game.vp"));
        SafeFiles.deleteTree(dir);
    }
    private static Mod mod(String id,String version,String type,String... deps) {
        Mod m = new Mod(); m.id=id; m.version=version; m.type=type; m.modFlag.add(id);
        Models.Package p=new Models.Package(); p.name="core"; p.status="required"; m.packages.add(p);
        for(int i=0;i<deps.length;i+=2) { Dependency d=new Dependency(); d.id=deps[i]; d.version=deps[i+1]; p.dependencies.add(d); }
        return m;
    }
    private static class Repo implements Resolver.Repository {
        final List<Mod> mods= new ArrayList<>();
        int engineReads;
        Repo(Mod... values) { mods.addAll(Arrays.asList(values)); }
        public List<Summary> versions(String id) {
            List<Summary> results=new ArrayList<>(); for(Mod m:mods)if(m.id.equals(id))results.add(m); return results;
        }
        public Mod mod(String id,String version) throws IOException {
            for(Mod m:mods) if(m.id.equals(id)&&m.version.equals(version)) {
                if("engine".equals(m.type))engineReads++; return m;
            }
            throw new IOException("Missing fixture");
        }
    }
    @Test public void dependencyResolutionBacktracksAndIgnoresAllEngineIds() throws Exception {
        Mod root=mod("root","1.0.0","tc","A","*","B","*","CustomEngine","*");
        Repo repo=new Repo(root,mod("A","2.0.0","mod","C",">=2.0.0"),mod("A","1.0.0","mod","C","<2.0.0"),
            mod("B","1.0.0","mod","C","<2.0.0"),mod("C","1.0.0","mod"),mod("C","2.0.0","mod"),
            mod("CustomEngine","8.0.0","engine"));
        Resolver.Plan plan=new Resolver(repo,true,"arm64-v8a").resolve("root","*");
        assertEquals(4,plan.selections.size());
        assertEquals("1.0.0",plan.selections.stream().filter(s->s.mod.id.equals("A")).findFirst().get().mod.version);
        assertEquals(0,repo.engineReads);
    }
    @Test public void forcedOptionalPackageAddsTransitiveDependencies() throws Exception {
        Mod root=mod("root","1.0.0","tc","data","*");
        root.packages.get(0).dependencies.get(0).packages.add("extras");
        Mod data=mod("data","1.0.0","mod");
        Models.Package extras=new Models.Package(); extras.name="extras"; extras.status="optional";
        Dependency d=new Dependency(); d.id="sounds"; d.version="^1.0.0"; extras.dependencies.add(d); data.packages.add(extras);
        Resolver.Plan plan=new Resolver(new Repo(root,data,mod("sounds","1.2.0","mod")),true,"arm64-v8a").resolve("root","*");
        assertEquals(3,plan.selections.size());
        assertEquals(2,plan.selections.stream().filter(s->s.mod.id.equals("data")).findFirst().get().packages.size());
    }
    @Test public void cyclesResolveButConflictsFail() throws Exception {
        Repo cyclic = new Repo(mod("A","1.0.0","tc","B","*"),mod("B","1.0.0","mod","A","*"));
        assertEquals(2,new Resolver(cyclic,true,"arm64-v8a").resolve("A","*").selections.size());
        Repo conflict=new Repo(mod("A","1.0.0","tc","B",">=2.0.0"),mod("B","1.0.0","mod"));
        try { new Resolver(conflict,true,"arm64-v8a").resolve("A","*"); fail(); } catch(IOException expected) {}
    }
    @Test public void realNebulaMetadataResolvesBothGamesWithoutEngineDownloads() throws Exception {
        Index index = GSON.fromJson(Files.newBufferedReader(Paths.get("fixtures/repo_minimal.json")),Index.class);
        Mod fotg=GSON.fromJson(Files.newBufferedReader(Paths.get("fixtures/fotg-api.json")),Reply.class).mod;
        Mod demo=GSON.fromJson(Files.newBufferedReader(Paths.get("fixtures/fs2demo-api.json")),Reply.class).mod;
        Repo repo=new Repo(fotg,demo);
        Resolver.Plan plan=new Resolver(repo,true,"arm64-v8a").resolve("FotG_R",">=1.1.1");
        assertEquals(11,plan.selections.get(0).packages.size());
        assertEquals(2571863909L,plan.downloadBytes());
        assertEquals(2,new Resolver(repo,true,"arm64-v8a").resolve("fs2_org_demo","*").selections.get(0).packages.size());
    }
    private Mod zipMod(Path root,String version, String entry, byte[] data) throws Exception {
        Mod m=mod("game",version,"tc");
        Path zip=Files.createTempFile("fixture",".zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(entry));out.write(data);out.closeEntry();
        }
        Archive a=new Archive(); a.filename="core.zip"; a.dest=""; a.filesize=Files.size(zip);
        a.checksum=Arrays.asList("sha256",SafeFiles.sha256(zip)); a.urls=Collections.emptyList();
        Files.createDirectories(root.resolve("downloads"));
        Files.move(zip,root.resolve("downloads").resolve(a.checksum.get(1)+".archive"));
        Member f=new Member(); f.filename=entry; f.originalName=entry; f.archive=a.filename;
        Path raw=Files.createTempFile("member",".dat");Files.write(raw,data);
        f.checksum=Arrays.asList("sha256",SafeFiles.sha256(raw));Files.delete(raw);
        m.packages.get(0).files.add(a);m.packages.get(0).filelist.add(f);
        return m;
    }
    private Mod sevenZMod(Path root,String version,String entry,byte[] data,boolean corrupt) throws Exception {
        Mod m=mod("game",version,"tc");
        Path archive=Files.createTempFile("fixture",".7z");
        try {
            try (SevenZOutputFile out=new SevenZOutputFile(archive.toFile())) {
                out.setContentCompression(SevenZMethod.COPY);
                SevenZArchiveEntry file=new SevenZArchiveEntry();file.setName(entry);file.setSize(data.length);
                out.putArchiveEntry(file);out.write(data);out.closeArchiveEntry();
            }
            if (corrupt) {
                // Alter the stored payload after the 32-byte signature header, preserving its original CRC.
                byte[] bytes=Files.readAllBytes(archive);bytes[32]^=1;Files.write(archive,bytes);
            }
            Archive a=new Archive();a.filename="core.7z";a.dest="";a.filesize=Files.size(archive);
            // The download hash matches even in the corrupt case, so extraction must detect the bad CRC.
            a.checksum=Arrays.asList("sha256",SafeFiles.sha256(archive));a.urls=Collections.emptyList();
            Files.createDirectories(root.resolve("downloads"));
            Files.move(archive,root.resolve("downloads").resolve(a.checksum.get(1)+".archive"));
            Member f=new Member();f.filename=entry;f.originalName=entry;f.archive=a.filename;
            m.packages.get(0).files.add(a);m.packages.get(0).filelist.add(f);
            return m;
        } finally { Files.deleteIfExists(archive); }
    }
    @Test public void zipAndSevenZExtractionDoNotVerifyIndividualFileChecksums() throws Exception {
        for (boolean sevenZ : new boolean[]{false,true}) {
            Path root=Files.createTempDirectory("member-checksum");
            try {
                byte[] data="game data".getBytes();
                Mod game=sevenZ ? sevenZMod(root,"1.0.0","data/test.tbl",data,false)
                    : zipMod(root,"1.0.0","data/test.tbl",data);
                game.packages.get(0).filelist.get(0).checksum=Arrays.asList("sha256","0".repeat(64));
                ContentInstaller installer=new ContentInstaller(root,new HttpTransport(NOOP),NOOP);
                Models.State state=installer.install(new Resolver(new Repo(game),true,"arm64-v8a").resolve("game","*"));
                assertArrayEquals(data,Files.readAllBytes(root.resolve(state.mods.get(0).directory).resolve("data/test.tbl")));
            } finally { SafeFiles.deleteTree(root); }
        }
    }
    @Test public void failedUpdatePreservesOldGraphAndSuccessfulUpdateReplacesIt() throws Exception {
        Path root=Files.createTempDirectory("installer");
        try {
            ContentInstaller installer=new ContentInstaller(root,new HttpTransport(NOOP),NOOP);
            Mod first=zipMod(root,"1.0.0","data/ships.tbl","first".getBytes());
            installer.install(new Resolver(new Repo(first),true,"arm64-v8a").resolve("game","*"));
            String old=installer.state().mods.get(0).directory;
            Mod broken=sevenZMod(root,"2.0.0","data/ships.tbl","broken".getBytes(),true);
            try { installer.install(new Resolver(new Repo(broken),true,"arm64-v8a").resolve("game","*")); fail(); }
            catch(IOException expected) { assertTrue(expected.getMessage(),expected.getMessage().contains("Checksum")); }
            assertEquals("1.0.0",installer.state().rootVersion);
            assertTrue(Files.isDirectory(root.resolve(old)));
            Mod second=zipMod(root,"2.0.0","data/ships.tbl","second".getBytes());
            installer.install(new Resolver(new Repo(second),true,"arm64-v8a").resolve("game","*"));
            assertEquals("2.0.0",installer.state().rootVersion);
            assertFalse(Files.exists(root.resolve(old)));
            assertTrue(installer.current(new Resolver(new Repo(second),true,"arm64-v8a").resolve("game","*")));
        } finally { SafeFiles.deleteTree(root); }
    }
    @Test public void archiveTraversalCannotActivateContent() throws Exception {
        Path root=Files.createTempDirectory("traversal");
        try {
            Mod attack=zipMod(root,"1.0.0","../outside","attack".getBytes());
            ContentInstaller installer=new ContentInstaller(root,new HttpTransport(NOOP),NOOP);
            try { installer.install(new Resolver(new Repo(attack),true,"arm64-v8a").resolve("game","*")); fail(); }
            catch(IOException expected) {}
            assertNull(installer.state());
        } finally {SafeFiles.deleteTree(root);}
    }
    @Test public void archiveDestinationIsAppliedWithoutDuplicatingDeclaredPrefix() throws Exception {
        Path root=Files.createTempDirectory("destination");
        try {
            Mod game=zipMod(root,"1.0.0","data/test.tbl","test".getBytes());
            game.packages.get(0).files.get(0).dest="nested";
            ContentInstaller installer=new ContentInstaller(root,new HttpTransport(NOOP),NOOP);
            Models.State state=installer.install(new Resolver(new Repo(game),true,"arm64-v8a").resolve("game","*"));
            assertTrue(Files.exists(root.resolve(state.mods.get(0).directory).resolve("nested/data/test.tbl")));
            assertFalse(Files.exists(root.resolve(state.mods.get(0).directory).resolve("data/test.tbl")));
        } finally {SafeFiles.deleteTree(root);}
    }
    @Test public void unchangedRootStillUpdatesItsDependencies() throws Exception {
        Path root=Files.createTempDirectory("dependency-update");
        try {
            Mod game=mod("root","1.0.0","tc","game","*");
            Mod data1=zipMod(root,"1.0.0","data/test.tbl","first".getBytes());
            ContentInstaller installer=new ContentInstaller(root,new HttpTransport(NOOP),NOOP);
            Models.State first=installer.install(new Resolver(new Repo(game,data1),true,"arm64-v8a").resolve("root","*"));
            String oldRoot=first.mods.stream().filter(m->m.id.equals("root")).findFirst().get().directory;
            Mod data2=zipMod(root,"2.0.0","data/test.tbl","second".getBytes());
            Resolver.Plan update=new Resolver(new Repo(game,data1,data2),true,"arm64-v8a").resolve("root","*");
            assertFalse(installer.current(update));
            Models.State second=installer.install(update);
            assertEquals("1.0.0",second.rootVersion);
            assertEquals("2.0.0",second.mods.stream().filter(m->m.id.equals("game")).findFirst().get().version);
            assertEquals(oldRoot,second.mods.stream().filter(m->m.id.equals("root")).findFirst().get().directory);
        } finally {SafeFiles.deleteTree(root);}
    }
    private static final class PackageObserver implements HttpTransport.Progress {
        final List<PackageProgress> snapshots = new ArrayList<>();
        final List<Integer> installedWhileExtracting = new ArrayList<>();
        PackageProgress latest = new PackageProgress(0,0,null);
        @Override public void update(String message,long completed,long total) {
            if (message.startsWith("Installed ")) installedWhileExtracting.add(latest.installed);
        }
        @Override public void packages(int installed,int total,String name) {
            latest = new PackageProgress(installed,total,name);
            snapshots.add(latest);
        }
    }
    @Test public void countsPackagesAcrossDependenciesAndIncludesReusedPackages() throws Exception {
        Path root=Files.createTempDirectory("package-progress");
        try {
            Mod game=mod("root","1.0.0","tc","game","*");
            Mod data=zipMod(root,"1.0.0","data/test.tbl","data".getBytes());
            Resolver.Plan plan=new Resolver(new Repo(game,data),true,"arm64-v8a").resolve("root","*");
            PackageObserver observer=new PackageObserver();
            ContentInstaller installer=new ContentInstaller(root,new HttpTransport(observer),observer);
            installer.install(plan);
            assertEquals(0,observer.snapshots.get(0).installed);
            assertEquals(2,observer.snapshots.get(0).total);
            assertTrue(observer.snapshots.stream().anyMatch(s->s.installed==1 && s.remaining()==1));
            assertEquals(2,observer.latest.installed); assertEquals(0,observer.latest.remaining());
            PackageObserver reuseObserver=new PackageObserver();
            new ContentInstaller(root,new HttpTransport(reuseObserver),reuseObserver).install(plan);
            assertEquals(2,reuseObserver.latest.installed);
            assertTrue(reuseObserver.installedWhileExtracting.isEmpty());
        } finally {SafeFiles.deleteTree(root);}
    }
    @Test public void countsOnePackageOnlyAfterAllItsArchivesVerify() throws Exception {
        Path root=Files.createTempDirectory("package-archives");
        try {
            Mod first=zipMod(root,"1.0.0","data/one.tbl","one".getBytes());
            Mod extra=zipMod(root,"1.0.0","data/two.tbl","two".getBytes());
            extra.packages.get(0).files.get(0).filename="extra.zip";
            extra.packages.get(0).filelist.get(0).archive="extra.zip";
            first.packages.get(0).files.addAll(extra.packages.get(0).files);
            first.packages.get(0).filelist.addAll(extra.packages.get(0).filelist);
            PackageObserver observer=new PackageObserver();
            new ContentInstaller(root,new HttpTransport(observer),observer).install(
                new Resolver(new Repo(first),true,"arm64-v8a").resolve("game","*"));
            assertEquals(Arrays.asList(0,0),observer.installedWhileExtracting);
            assertEquals(1,observer.latest.installed);
            assertEquals(1,observer.latest.total);
        } finally {SafeFiles.deleteTree(root);}
    }
    @Test public void failedPackageDoesNotAdvanceTheInstalledCount() throws Exception {
        Path root=Files.createTempDirectory("package-failure");
        try {
            Mod game=sevenZMod(root,"1.0.0","data/test.tbl","bad".getBytes(),true);
            PackageObserver observer=new PackageObserver();
            try {
                new ContentInstaller(root,new HttpTransport(observer),observer).install(
                    new Resolver(new Repo(game),true,"arm64-v8a").resolve("game","*"));
                fail();
            } catch(IOException expected) { assertTrue(expected.getMessage(),expected.getMessage().contains("Checksum")); }
            assertEquals(0,observer.latest.installed);
            assertEquals(1,observer.latest.remaining());
        } finally {SafeFiles.deleteTree(root);}
    }
}
