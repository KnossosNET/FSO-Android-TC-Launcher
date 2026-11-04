package com.fsolauncher.app;

import android.content.Context;
import android.os.Build;
import com.google.gson.Gson;
import com.fsolauncher.core.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

public final class GameSpec {
    public String displayName, nebulaId, contentRange;
    public boolean includeRecommended = true;
    public List<String> arguments = new ArrayList<>();
    public List<String> repositoryMirrors = new ArrayList<>(NebulaRepository.DEFAULT_REPOS);
    public List<String> apiMirrors = new ArrayList<>(NebulaRepository.DEFAULT_APIS);
    public static GameSpec load(Context context) throws IOException {
        try (Reader reader = new InputStreamReader(context.getAssets().open("game.json"), java.nio.charset.StandardCharsets.UTF_8)) {
            GameSpec spec = new Gson().fromJson(reader, GameSpec.class);
            if (spec == null || spec.nebulaId == null || spec.displayName == null) throw new IOException("Invalid game configuration");
            SafeFiles.component(spec.nebulaId); return spec;
        }
    }
    public static Path root(Context context) throws IOException {
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("Game storage is unavailable");
        Path root = external.toPath().resolve("game");
        java.nio.file.Files.createDirectories(root); return root;
    }
    public static String abi() {
        return Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
    }
}
