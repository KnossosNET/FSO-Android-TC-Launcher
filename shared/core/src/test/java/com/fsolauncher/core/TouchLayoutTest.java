package com.fsolauncher.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TouchLayoutTest {
    private String shared() throws Exception {
        return Files.readString(Path.of("shared/touch-default.json"));
    }

    private TouchLayout.Element element(TouchLayout layout, String id) {
        return layout.elements.stream().filter(e -> id.equals(e.id)).findFirst().orElseThrow();
    }

    @Test public void sharedLayoutReproducesExistingGroupsAndCenterProportions() throws Exception {
        TouchLayout layout = TouchLayout.parse(shared(), null);
        assertEquals(6, layout.elements.size());
        TouchLayout.Element communication = element(layout, "communicationWheel");
        TouchLayout.Element target = element(layout, "targetWheel");
        TouchLayout.Element weapons = element(layout, "weaponWheel");
        assertEquals(1, communication.center.size());
        assertEquals(2, target.center.size());
        assertEquals(3, weapons.center.size());
        assertEquals(11, weapons.outer.size());
        assertEquals(.16f, weapons.center.get(1).weight, .0001f);
        assertArrayEquals(new int[]{21, 20}, weapons.center.get(1).codes);
        assertFalse(TouchLayout.visible(communication.modes, 1));
        assertTrue(TouchLayout.visible(communication.modes, 2));
        assertFalse(TouchLayout.visible(target.modes, 2));
        assertEquals("TAB", element(layout, "dpad").ringAction.label);
    }

    @Test public void existingProfilesAreValidAndLegacyProfilesInheritDefaults() throws Exception {
        try (var modules = Files.newDirectoryStream(Path.of("versions"))) {
            for (Path module : modules) {
                if (!Files.isRegularFile(module.resolve("build.gradle"))) continue;
                Path profile = module.resolve("src/main/assets/touch.json");
                String json = Files.exists(profile) ? Files.readString(profile) : null;
                assertNotNull(TouchLayout.parse(shared(), json).elements);
            }
        }
        assertEquals(6, TouchLayout.parse(shared(), "{}").elements.size());
        assertEquals(6, TouchLayout.parse(shared(),
            "{\"initialHudMode\":3,\"codeMap\":{},\"actions\":{},\"buttons\":{}}").elements.size());
    }

    @Test public void legacyOverridesKeepTheirPrecedenceAndAllowDisabledInput() throws Exception {
        TouchLayout layout = TouchLayout.parse(shared(), """
            {"codeMap":{"21":[],"20":[40]},
             "actions":{"BOTH":{"label":"Fire","codes":[1008]}},
             "buttons":{"btnF1":{"label":"Jump","codes":[1040]}}}
            """);
        List<TouchLayout.Action> center = element(layout, "weaponWheel").center;
        assertArrayEquals(new int[0], layout.resolve(center.get(0), false).codes);
        assertArrayEquals(new int[]{1008}, layout.resolve(center.get(1), false).codes);
        assertEquals("Fire", layout.resolve(center.get(1), false).label);
        assertArrayEquals(new int[]{40}, layout.resolve(center.get(2), false).codes);
        TouchLayout.Action f1 = element(layout, "topBarLeftScroller").buttons.get(1);
        assertEquals("Jump", layout.resolve(f1, true).label);
        assertArrayEquals(new int[]{1040}, layout.resolve(f1, true).codes);
    }

    @Test public void fullTcLayoutReplacesElementsAndSupportsJoystickWithoutRing() throws Exception {
        TouchLayout layout = TouchLayout.parse(shared(), """
            {"schemaVersion":1,"initialHudMode":1,"elements":[
              {"id":"customStick","type":"joystick","modes":[1,3],
               "position":{"x":1,"y":1},"size":{"heightDp":140}}
            ]}
            """);
        assertEquals(1, layout.elements.size());
        assertEquals(1, layout.initialHudMode);
        assertNull(layout.elements.get(0).ringAction);
        assertTrue(TouchLayout.visible(layout.elements.get(0).modes, 1));
        assertFalse(TouchLayout.visible(layout.elements.get(0).modes, 2));
        assertTrue(TouchLayout.parse(shared(), "{\"elements\":[]}").elements.isEmpty());
    }

    @Test public void mouseButtonCodeRangeIsValidated() throws Exception {
        TouchLayout profile = TouchLayout.parse(shared(), "{\"codeMap\":{\"21\":[2001,2002,2003]}}");
        assertArrayEquals(new int[]{2001, 2002, 2003},
            profile.resolve(element(profile, "weaponWheel").center.get(0), false).codes);
        assertThrows(IllegalArgumentException.class,
            () -> TouchLayout.parse(shared(), "{\"codeMap\":{\"21\":[2004]}}"));
    }

    @Test public void geometryKeepsModeButtonGapAndUsesViewportHeightWithCaps() throws Exception {
        TouchLayout layout = TouchLayout.parse(shared(), null);
        TouchLayout.Bounds left = TouchLayout.bounds(element(layout, "topBarLeftScroller"), 1000, 500, 1);
        TouchLayout.Bounds right = TouchLayout.bounds(element(layout, "topBarRightScroller"), 1000, 500, 1);
        assertEquals(481, left.width);
        assertEquals(519, right.x);
        assertEquals(38, right.x - left.width);
        TouchLayout.Bounds stick = TouchLayout.bounds(element(layout, "dpad"), 1000, 500, 1);
        assertEquals(290, stick.height);
        assertEquals(290, stick.width);
        assertEquals(204, stick.y);
        TouchLayout.Bounds capped = TouchLayout.bounds(element(layout, "dpad"), 2000, 1000, 1);
        assertEquals(330, capped.height);
    }

    @Test public void controlsStayInsideSmallSafeViewports() throws Exception {
        for (TouchLayout.Element element : TouchLayout.parse(shared(), null).elements) {
            TouchLayout.Bounds bounds = TouchLayout.bounds(element, 120, 100, 3);
            assertTrue(bounds.x >= 0 && bounds.y >= 0);
            assertTrue(bounds.x + bounds.width <= 120);
            assertTrue(bounds.y + bounds.height <= 100);
        }
    }

    @Test public void invalidLayoutsAreRejectedBeforeViewsAreCreated() throws Exception {
        String defaults = shared();
        for (String invalid : List.of(
                "{\"initialHudMode\":9}",
                "{\"schemaVersion\":2}",
                "{\"buttons\":{\"btnToggle\":{\"codes\":[1]}}}",
                "{\"elements\":null}",
                "{\"codeMap\":{\"21\":[999]}}",
                "{\"elements\":[{\"id\":\"btnToggle\",\"type\":\"button\"}]}",
                "{\"elements\":[{\"id\":\"unknown\",\"type\":\"unsupported\"}]}")) {
            assertThrows(RuntimeException.class, () -> TouchLayout.parse(defaults, invalid));
        }
        JsonObject json = JsonParser.parseString(defaults).getAsJsonObject();
        JsonObject radial = json.getAsJsonArray("elements").get(2).getAsJsonObject();
        for (int i = 0; i < 3; i++) radial.getAsJsonArray("center").add(radial.getAsJsonArray("center").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> TouchLayout.parse(json.toString(), null));
    }
}
