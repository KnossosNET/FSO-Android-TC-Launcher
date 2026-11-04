package com.fsolauncher.core;

import java.util.ArrayList;
import java.util.List;

/** Applies launcher-owned switches once, overriding conflicting per-game defaults. */
public final class LaunchArguments {
    private LaunchArguments() {}
    public static ArrayList<String> build(List<String> defaults, boolean vulkan, boolean showFps,
                                          List<String> modPaths) {
        ArrayList<String> result = new ArrayList<>();
        for (String argument : Models.list(defaults)) {
            if (!"-vulkan".equalsIgnoreCase(argument) && !"-opengl".equalsIgnoreCase(argument)
                    && !"-fps".equalsIgnoreCase(argument)) result.add(argument);
        }
        result.add(vulkan ? "-vulkan" : "-opengl");
        if (showFps) result.add("-fps");
        result.add("-mod");
        result.add(String.join(",", modPaths));
        return result;
    }
}
