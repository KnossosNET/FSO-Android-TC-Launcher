package com.fsolauncher.core;

/** Immutable progress shared between the installer thread, launcher and notification. */
public final class PackageProgress {
    public final int installed, total;
    public final String currentPackage;
    public PackageProgress(int installed, int total, String currentPackage) {
        if (installed < 0 || total < installed) throw new IllegalArgumentException("Invalid package progress");
        this.installed = installed; this.total = total; this.currentPackage = currentPackage;
    }
    public int remaining() { return total - installed; }
    public String summary() {
        return "Packages installed: " + installed + "/" + total + " · Remaining: " + remaining();
    }
}
