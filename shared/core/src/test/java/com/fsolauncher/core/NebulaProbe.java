package com.fsolauncher.core;

import java.nio.file.*;
import java.util.*;

/** Small live integration probe: downloads and verifies only FotG's core package. */
public final class NebulaProbe {
    public static void main(String[] args) throws Exception {
        Path fixture=Paths.get(args[0]), root=fixture.resolve("probe-install");
        HttpTransport.Progress progress=(m,d,t)->{};
        HttpTransport transport=new HttpTransport(progress);
        NebulaRepository repo=new NebulaRepository(root.resolve("metadata"),transport,
            NebulaRepository.DEFAULT_REPOS,NebulaRepository.DEFAULT_APIS);
        if (args.length > 1 && "demo".equals(args[1])) {
            Resolver.Plan demo = new Resolver(repo,true,"arm64-v8a").resolve("fs2_org_demo",">=1.10.2");
            Models.State state = new ContentInstaller(root,transport,progress).install(demo);
            if (state.mods.size() != 1 || !state.rootId.equals("fs2_org_demo"))
                throw new IllegalStateException("Invalid demo installation");
            System.out.println("Verified full live FS2 Demo download, archive checksums and extraction: " + state.rootVersion);
            SafeFiles.deleteTree(root); return;
        }
        Resolver.Plan full=new Resolver(repo,true,"arm64-v8a").resolve("FotG_R",">=1.1.1");
        Models.Mod mod=repo.mod("FotG_R",full.rootVersion);
        Models.Package core=mod.packages.stream().filter(p->p.name.equals("FG_Core")).findFirst().get();
        Resolver.Plan small=new Resolver.Plan(mod.id,mod.version,
            Collections.singletonList(new Resolver.Selection(mod,Collections.singletonList(core))));
        Models.State installed=new ContentInstaller(root,transport,progress).install(small);
        Path table=SafeFiles.resolve(root,installed.modPaths.get(0)).resolve("data/tables");
        if(!Files.isDirectory(table))throw new IllegalStateException("No extracted tables");
        System.out.println("Verified live Nebula index, full FotG graph, FG_Core 7z archive checksums and extraction: "+installed.rootVersion);
        SafeFiles.deleteTree(root);
    }
}
