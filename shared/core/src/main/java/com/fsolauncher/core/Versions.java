package com.fsolauncher.core;

import java.math.BigInteger;
import java.util.regex.*;

/** SemVer precedence and the dependency range syntax used by Nebula. */
public final class Versions {
    private Versions() {}
    private static final Pattern VERSION = Pattern.compile("^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");
    private static String[] parse(String text) {
        Matcher m = VERSION.matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("Invalid version: " + text);
        return new String[] { m.group(1), m.group(2) == null ? "0" : m.group(2),
            m.group(3) == null ? "0" : m.group(3), m.group(4) };
    }
    public static int compare(String left, String right) {
        String[] a = parse(left), b = parse(right);
        for (int i = 0; i < 3; i++) {
            int c = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
            if (c != 0) return c;
        }
        if (a[3] == null || b[3] == null) return a[3] == b[3] ? 0 : a[3] == null ? 1 : -1;
        String[] ap = a[3].split("\\."), bp = b[3].split("\\.");
        for (int i = 0; i < Math.min(ap.length, bp.length); i++) {
            String x = ap[i], y = bp[i];
            boolean nx = x.matches("\\d+"), ny = y.matches("\\d+");
            int c = nx && ny ? new BigInteger(x).compareTo(new BigInteger(y)) :
                nx != ny ? (nx ? -1 : 1) : x.compareTo(y);
            if (c != 0) return c;
        }
        return Integer.compare(ap.length, bp.length);
    }
    public static boolean matches(String value, String range) {
        if (range == null || range.trim().isEmpty() || range.equals("*")) return true;
        for (String alternative : range.split("\\|\\|")) {
            String r = alternative.trim();
            Matcher hyphen = Pattern.compile("^(\\S+)\\s+-\\s+(\\S+)$").matcher(r);
            if (hyphen.matches()) r = ">=" + hyphen.group(1) + " <=" + hyphen.group(2);
            r = r.replaceAll("(>=|<=|>|<|=|\\^|~)\\s+", "$1").replace(',', ' ');
            boolean ok = true;
            for (String token : r.split("\\s+")) if (!token.isEmpty()) ok &= token(value, token);
            if (ok) return true;
        }
        return false;
    }
    private static boolean token(String value, String token) {
        Matcher m = Pattern.compile("^(>=|<=|>|<|==|=|\\^|~)?(.*)$").matcher(token);
        if (!m.matches()) throw new IllegalArgumentException("Unsupported range: " + token);
        String op = m.group(1) == null ? "" : m.group(1), target = m.group(2);
        if (target.equals("*") || target.equalsIgnoreCase("x")) return true;
        if (target.matches(".*[xX*].*")) {
            if (!op.isEmpty() && !op.equals("=")) throw new IllegalArgumentException("Invalid wildcard range: " + token);
            String[] components = target.split("\\."), actual = parse(value);
            for (int i = 0; i < components.length; i++) {
                if (components[i].matches("[xX*]")) return true;
                if (i >= 3 || !new BigInteger(components[i]).equals(new BigInteger(actual[i]))) return false;
            }
            return true;
        }
        int c = compare(value, target);
        switch (op) {
            case ">=": return c >= 0;
            case "<=": return c <= 0;
            case ">": return c > 0;
            case "<": return c < 0;
            case "^": case "~":
                String[] p = parse(target);
                BigInteger major = new BigInteger(p[0]), minor = new BigInteger(p[1]), patch = new BigInteger(p[2]);
                String upper;
                if (op.equals("~")) upper = target.split("\\.").length == 1 ?
                    major.add(BigInteger.ONE) + ".0.0" : major + "." + minor.add(BigInteger.ONE) + ".0";
                else if (major.signum() > 0 || target.split("\\.").length == 1) upper = major.add(BigInteger.ONE) + ".0.0";
                else if (minor.signum() > 0 || target.split("\\.").length == 2) upper = "0." + minor.add(BigInteger.ONE) + ".0";
                else upper = "0.0." + patch.add(BigInteger.ONE);
                return c >= 0 && compare(value, upper) < 0;
            default:
                if (!op.isEmpty() || target.contains("-") || target.split("\\.").length == 3) return c == 0;
                String[] wanted = target.split("\\."), actual = parse(value);
                for (int i = 0; i < wanted.length; i++)
                    if (!new BigInteger(wanted[i]).equals(new BigInteger(actual[i]))) return false;
                return true;
        }
    }
}
