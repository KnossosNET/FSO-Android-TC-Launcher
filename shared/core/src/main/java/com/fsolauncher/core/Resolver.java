package com.fsolauncher.core;

import java.io.IOException;
import java.util.*;
import static com.fsolauncher.core.Models.list;

/** Resolves a complete, consistent content graph. Engine releases never become downloads. */
public final class Resolver {
    public interface Repository {
        List<Models.Summary> versions(String id) throws IOException;
        Models.Mod mod(String id, String version) throws IOException;
    }
    public static final class Selection {
        public final Models.Mod mod;
        public final List<Models.Package> packages;
        Selection(Models.Mod mod, List<Models.Package> packages) { this.mod = mod; this.packages = packages; }
    }
    public static final class Plan {
        public final String rootId, rootVersion;
        public final List<Selection> selections;
        Plan(String id, String version, List<Selection> selections) {
            rootId = id; rootVersion = version; this.selections = selections;
        }
        public long downloadBytes() {
            return selections.stream().flatMap(s -> s.packages.stream())
                .flatMap(p -> list(p.files).stream()).mapToLong(f -> f.filesize).sum();
        }
    }
    private static final class Requirement {
        final List<String> ranges = new ArrayList<>();
        final Set<String> packages = new LinkedHashSet<>();
    }
    private final Repository repository;
    private final boolean recommended;
    private final String abi;
    private String failure = "No compatible content version";
    private int attempts;
    public Resolver(Repository repository, boolean recommended, String abi) {
        this.repository = repository; this.recommended = recommended; this.abi = abi;
    }
    public Plan resolve(String id, String range) throws IOException {
        SafeFiles.component(id);
        attempts = 0;
        Map<String, Models.Mod> solution = solve(id, range, new LinkedHashMap<>());
        if (solution == null) throw new IOException("Cannot resolve dependencies: " + failure);
        Map<String, Requirement> closure = closure(id, range, solution);
        List<Selection> selected = new ArrayList<>();
        for (String key : closure.keySet()) selected.add(new Selection(solution.get(key), packages(solution.get(key), closure.get(key))));
        return new Plan(id, solution.get(id).version, selected);
    }
    private boolean engine(String id) throws IOException {
        if (id.equalsIgnoreCase("FSO")) return true;
        List<Models.Summary> summaries = repository.versions(id);
        return !summaries.isEmpty() && summaries.stream().allMatch(s -> "engine".equals(s.type));
    }
    private void require(Map<String, Requirement> requirements, String id, String range, List<String> packages) throws IOException {
        SafeFiles.component(id);
        if (engine(id)) return;
        Requirement req = requirements.computeIfAbsent(id, k -> new Requirement());
        if (range != null && !req.ranges.contains(range)) req.ranges.add(range);
        req.packages.addAll(list(packages));
    }
    private List<Models.Package> packages(Models.Mod mod, Requirement req) throws IOException {
        List<Models.Package> selected = new ArrayList<>();
        Set<String> found = new HashSet<>();
        for (Models.Package p : list(mod.packages)) {
            boolean needed = req.packages.contains(p.name) || "required".equals(p.status) ||
                (recommended && (p.status == null || "recommended".equals(p.status)));
            if (needed && Environment.matches(p.environment, abi)) { selected.add(p); found.add(p.name); }
        }
        if (!found.containsAll(req.packages)) throw new IOException("Missing Android dependency packages for " + mod.id + ": " + req.packages);
        if (selected.isEmpty()) throw new IOException("No Android data packages for " + mod.id);
        return selected;
    }
    private Map<String, Requirement> closure(String id, String range, Map<String, Models.Mod> assigned) throws IOException {
        Map<String, Requirement> result = new LinkedHashMap<>();
        require(result, id, range, Collections.emptyList());
        boolean changed;
        do {
            String before = fingerprint(result);
            for (String key : new ArrayList<>(result.keySet())) {
                Models.Mod mod = assigned.get(key);
                if (mod == null) continue;
                for (Models.Package p : packages(mod, result.get(key)))
                    for (Models.Dependency d : list(p.dependencies)) require(result, d.id, d.version, d.packages);
            }
            changed = !fingerprint(result).equals(before);
        } while (changed);
        return result;
    }
    private String fingerprint(Map<String, Requirement> requirements) {
        StringBuilder text = new StringBuilder();
        requirements.forEach((id,r) -> text.append(id).append(r.ranges).append(r.packages));
        return text.toString();
    }
    private Map<String, Models.Mod> solve(String id, String range, Map<String, Models.Mod> assigned) throws IOException {
        SafeFiles.checkCancelled();
        if (++attempts > 10000) throw new IOException("Dependency graph exceeds resolution limit");
        Map<String, Requirement> requirements;
        try { requirements = closure(id, range, assigned); }
        catch (IOException e) { failure = e.getMessage(); return null; }
        for (Map.Entry<String, Requirement> entry : requirements.entrySet()) {
            Models.Mod mod = assigned.get(entry.getKey());
            if (mod != null && !entry.getValue().ranges.stream().allMatch(r -> Versions.matches(mod.version, r))) {
                failure = entry.getKey() + " requires " + entry.getValue().ranges;
                return null;
            }
        }
        String pending = requirements.keySet().stream().filter(k -> !assigned.containsKey(k)).findFirst().orElse(null);
        if (pending == null) return assigned;
        Requirement req = requirements.get(pending);
        List<Models.Summary> candidates = new ArrayList<>(repository.versions(pending));
        candidates.removeIf(s -> "engine".equals(s.type) || s.version == null ||
            !req.ranges.stream().allMatch(r -> Versions.matches(s.version, r)) ||
            (s.version.contains("-") && req.ranges.stream().noneMatch(r -> r.contains("-"))));
        candidates.sort((a,b) -> Versions.compare(b.version, a.version));
        if (candidates.isEmpty()) failure = pending + " requires " + req.ranges;
        for (Models.Summary s : candidates) {
            Models.Mod mod = repository.mod(pending, s.version);
            if ("engine".equals(mod.type)) throw new IOException("Engine metadata cannot be installed as content");
            Map<String, Models.Mod> next = new LinkedHashMap<>(assigned); next.put(pending, mod);
            Map<String, Models.Mod> solution = solve(id, range, next);
            if (solution != null) return solution;
        }
        return null;
    }
}
