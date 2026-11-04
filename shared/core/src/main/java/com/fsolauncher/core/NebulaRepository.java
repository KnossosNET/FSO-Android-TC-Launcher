package com.fsolauncher.core;

import com.google.gson.Gson;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static com.fsolauncher.core.Models.list;

public final class NebulaRepository implements Resolver.Repository {
    public static final List<String> DEFAULT_REPOS = Arrays.asList(
        "https://fsnebula.org/storage/repo_minimal.json",
        "https://cf.fsnebula.org/storage/repo_minimal.json",
        "https://fsnebula.space/storage/repo_minimal.json");
    public static final List<String> DEFAULT_APIS = Arrays.asList(
        "https://api.fsnebula.org/api/1/", "https://fsnebula.space/api/1/");
    private final Path cache;
    private final HttpTransport transport;
    private final List<String> apiMirrors;
    private final Map<String, List<Models.Summary>> index = new LinkedHashMap<>();
    private final Map<String, Models.Mod> metadata = new HashMap<>();
    private final Gson gson = new Gson();
    public NebulaRepository(Path cache, HttpTransport transport, List<String> repos, List<String> apis) throws IOException {
        this.cache = cache; this.transport = transport; this.apiMirrors = apis;
        Models.Index parsed = gson.fromJson(transport.json(repos, cache.resolve("repo_minimal.json"), true), Models.Index.class);
        if (parsed == null || parsed.mods == null) throw new IOException("Invalid Nebula repository");
        for (Models.Summary s : parsed.mods) {
            if (s.id != null && s.version != null) index.computeIfAbsent(s.id, k -> new ArrayList<>()).add(s);
        }
    }
    @Override public List<Models.Summary> versions(String id) { return index.getOrDefault(id, Collections.emptyList()); }
    @Override public Models.Mod mod(String id, String version) throws IOException {
        String key = id + "@" + version;
        if (metadata.containsKey(key)) return metadata.get(key);
        SafeFiles.component(id); SafeFiles.component(version);
        List<String> urls = new ArrayList<>();
        String endpoint = "mod/json/" + URLEncoder.encode(id, "UTF-8") + "/" + URLEncoder.encode(version, "UTF-8");
        for (String api : apiMirrors) urls.add(api + endpoint);
        Models.Reply reply = gson.fromJson(transport.json(urls, cache.resolve(id + "-" + version + ".json"), true), Models.Reply.class);
        if (reply == null || !reply.result || reply.mod == null || !id.equals(reply.mod.id) || !version.equals(reply.mod.version))
            throw new IOException("Nebula returned invalid mod metadata for " + key);
        metadata.put(key, reply.mod); return reply.mod;
    }
}
