package com.fsolauncher.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class LauncherOptionsTest {
    private List<String> arguments(boolean vulkan, boolean fps) {
        return LaunchArguments.build(Arrays.asList("-opengl", "-vulkan", "-fps", "-fps", "-threads", "0"),
            vulkan, fps, Arrays.asList("content/root", "content/dependency"));
    }
    @Test public void vulkanSelectionOverridesConflictingDefaults() {
        List<String> result = arguments(true, false);
        assertEquals(1, Collections.frequency(result,"-vulkan"));
        assertFalse(result.contains("-opengl"));
        assertFalse(result.contains("-fps"));
    }
    @Test public void esSelectionNeverKeepsTheVulkanFlag() {
        List<String> result = arguments(false, false);
        assertEquals(1, Collections.frequency(result,"-opengl"));
        assertFalse(result.contains("-vulkan"));
    }
    @Test public void fpsCheckboxAddsTheFlagExactlyOnce() {
        assertEquals(1, Collections.frequency(arguments(true,true),"-fps"));
        assertEquals(1, Collections.frequency(arguments(false,true),"-fps"));
    }
    @Test public void preservesTcArgumentsAndDependencyOrderWithoutMutatingDefaults() {
        List<String> defaults = Arrays.asList("-aa","-threads","0","-vulkan");
        assertEquals(Arrays.asList("-aa","-threads","0","-opengl","-mod","tc,base"),
            LaunchArguments.build(defaults,false,false,Arrays.asList("tc","base")));
        assertEquals(Arrays.asList("-aa","-threads","0","-vulkan"), defaults);
    }
    @Test public void readsExactFullVersionIncludingBuildSuffix() throws Exception {
        assertEquals("26.2.0-custom+build.7",
            EngineFlags.fullVersion("{\"version\":{\"full\":\"26.2.0-custom+build.7\",\"major\":26}}"));
    }
    @Test public void readsTheUserProvidedConsoleResponse() throws Exception {
        String sample = new String(Files.readAllBytes(Paths.get("fixtures/engine-flags-console.txt")),StandardCharsets.UTF_8);
        assertEquals("26.0.1",EngineFlags.fullVersion(sample));
    }
    @Test public void doesNotGuessVersionsWhenFullIsMissingOrInvalid() throws Exception {
        for (String response : Arrays.asList("{}", "{\"version\":{}}", "{\"version\":{\"full\":26}}",
                "{\"version\":{\"full\":\" \"}}","{\"version\":null}")) {
            try { EngineFlags.fullVersion(response); fail(response); } catch (IOException expected) {}
        }
    }
    @Test public void rejectsMalformedOrIncompleteJson() throws Exception {
        for (String response : Arrays.asList("not json", "{\"version\":{\"full\":\"26.2.0\"}", "{\"version\":[]}")) {
            try { EngineFlags.fullVersion(response); fail(response); } catch (IOException expected) {}
        }
    }
}
