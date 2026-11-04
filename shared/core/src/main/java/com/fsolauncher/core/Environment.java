package com.fsolauncher.core;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

final class Environment {
    private final List<String> tokens = new ArrayList<>();
    private int position;
    private final String abi;
    private Environment(String expression, String abi) throws IOException {
        this.abi = abi;
        Matcher m = Pattern.compile("\\s*(&&|\\|\\||!|\\(|\\)|[A-Za-z0-9_]+)\\s*").matcher(expression);
        int end = 0;
        while (m.find()) {
            if (m.start() != end) throw new IOException("Unsupported package environment: " + expression);
            tokens.add(m.group(1)); end = m.end();
        }
        if (end != expression.length()) throw new IOException("Unsupported package environment: " + expression);
    }
    static boolean matches(String expression, String abi) throws IOException {
        if (expression == null || expression.trim().isEmpty()) return true;
        Environment e = new Environment(expression.trim(), abi);
        boolean value = e.or();
        if (e.position != e.tokens.size()) throw new IOException("Invalid package environment: " + expression);
        return value;
    }
    private boolean eat(String value) {
        if (position < tokens.size() && tokens.get(position).equals(value)) { position++; return true; }
        return false;
    }
    private boolean or() throws IOException {
        boolean value = and();
        while (eat("||")) { boolean right = and(); value = value || right; }
        return value;
    }
    private boolean and() throws IOException {
        boolean value = unary();
        while (eat("&&")) { boolean right = unary(); value = value && right; }
        return value;
    }
    private boolean unary() throws IOException {
        if (eat("!")) return !unary();
        if (eat("(")) { boolean v = or(); if (!eat(")")) throw new IOException("Unclosed environment"); return v; }
        if (position >= tokens.size()) throw new IOException("Incomplete package environment");
        String token = tokens.get(position++).toLowerCase(Locale.ROOT);
        switch (token) {
            case "true": case "android": return true;
            case "false": case "windows": case "linux": case "macosx": case "macos": return false;
            case "arm64": case "aarch64": return abi.equals("arm64-v8a");
            case "arm32": case "arm": return abi.equals("armeabi-v7a");
            case "x86_64": case "amd64": return abi.equals("x86_64");
            case "x86": return abi.equals("x86");
            case "sse": case "sse2": return abi.startsWith("x86");
            case "avx": case "avx2": return false;
            default: throw new IOException("Unknown package environment symbol: " + token);
        }
    }
}
