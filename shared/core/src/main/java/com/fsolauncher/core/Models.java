package com.fsolauncher.core;

import com.google.gson.annotations.SerializedName;
import java.util.*;

public final class Models {
    private Models() {}
    public static class Index { public List<Summary> mods = new ArrayList<>(); }
    public static class Summary { public String id, version, type, title, stability; }
    public static class Reply { public boolean result; public String reason; public Mod mod; }
    public static class Mod extends Summary {
        public String parent, cmdline;
        @SerializedName("mod_flag") public List<String> modFlag = new ArrayList<>();
        @SerializedName("dev_mode") public boolean devMode;
        public List<Package> packages = new ArrayList<>();
    }
    public static class Package {
        public String name, folder, status, environment;
        public List<Dependency> dependencies = new ArrayList<>();
        public List<Archive> files = new ArrayList<>();
        public List<Member> filelist = new ArrayList<>();
    }
    public static class Dependency {
        public String id, version;
        public List<String> packages = new ArrayList<>();
    }
    public static class Archive {
        public String filename, dest;
        public long filesize;
        public List<String> checksum, urls;
    }
    public static class Member {
        public String filename, archive;
        @SerializedName("orig_name") public String originalName;
        public List<String> checksum;
    }
    public static class Installed {
        public String id, version, directory, manifestHash;
        public List<String> packages = new ArrayList<>();
        public List<String> searchPaths = new ArrayList<>();
    }
    public static class State {
        public int schema = 1;
        public String generation, rootId, rootVersion;
        public List<Installed> mods = new ArrayList<>();
        public List<String> modPaths = new ArrayList<>();
    }
    public static <T> List<T> list(List<T> value) { return value == null ? Collections.emptyList() : value; }
}
