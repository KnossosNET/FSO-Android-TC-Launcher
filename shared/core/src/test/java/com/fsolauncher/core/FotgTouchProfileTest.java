package com.fsolauncher.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.*;

public class FotgTouchProfileTest {
    private TouchLayout layout(String module) throws Exception {
        String shared = Files.readString(Path.of("shared/touch-default.json"));
        String profile = module == null ? null : Files.readString(Path.of("versions", module, "src/main/assets/touch.json"));
        return TouchLayout.parse(shared, profile);
    }

    private TouchLayout.Element element(TouchLayout layout, String id) {
        return layout.elements.stream().filter(e -> id.equals(e.id)).findFirst().orElseThrow();
    }

    private List<String> labels(List<TouchLayout.Action> actions) {
        return actions.stream().map(action -> action.label).collect(Collectors.toList());
    }

    @Test public void fotgWeaponsAndTargetsMatchTheGameBindings() throws Exception {
        TouchLayout fotg = layout("Fotg");
        TouchLayout.Element target = element(fotg, "targetWheel");
        assertEquals(1, target.center.size());
        assertArrayEquals(new int[]{20}, target.center.get(0).codes);
        assertEquals(List.of("H", "Y", "F", "V", "B", "Z"), labels(target.outer));
        assertEquals(90f, target.firstOuterActionAngle, .001f);
        assertEquals(1.35f, target.outer.get(0).weight, .001f);
        TouchLayout.Element weapons = element(fotg, "weaponWheel");
        assertEquals(2, weapons.center.size());
        assertArrayEquals(new int[]{2001}, weapons.center.get(0).codes);
        assertArrayEquals(new int[]{40}, weapons.center.get(1).codes);
        assertEquals(List.of("P", "E", "Q", "CAPS\nLOCK", "X", "W", "S", "}", "BACK\nSPACE"), labels(weapons.outer));
        // FSO's KEY_SLASH is the backslash scancode. This is MAX_THROTTLE in FotG.
        assertArrayEquals(new int[]{48}, weapons.outer.get(7).codes);
        assertNull(element(fotg, "dpad").ringAction);
        TouchLayout.Bounds sharedBounds = TouchLayout.bounds(element(layout(null), "weaponWheel"), 1600, 900, 2);
        TouchLayout.Bounds fotgBounds = TouchLayout.bounds(weapons, 1600, 900, 2);
        assertEquals(sharedBounds.width, fotgBounds.width);
        assertEquals(sharedBounds.height, fotgBounds.height);
    }

    @Test public void fotgTopBarIncludesTheRequestedKeyboardAndChordActions() throws Exception {
        TouchLayout fotg = layout("Fotg");
        List<TouchLayout.Action> left = element(fotg, "topBarLeftScroller").buttons;
        assertEquals(List.of("0", "F1", "F2", "F3", "F4", "ESC", "KEYB"), labels(left));
        assertArrayEquals(new int[]{1}, left.get(5).codes);
        assertEquals("keyboard", left.get(6).command);
        List<TouchLayout.Action> right = element(fotg, "topBarRightScroller").buttons;
        assertEquals(List.of("ALT+E", "ALT+Q", "ENTER", ";", "A", "D"), labels(right));
        assertArrayEquals(new int[]{11, 33}, right.get(0).codes);
        assertArrayEquals(new int[]{11, 43}, right.get(1).codes);
        assertArrayEquals(new int[]{1040}, right.get(2).codes);
        assertArrayEquals(new int[]{1051}, right.get(3).codes);
        assertEquals(labels(element(layout(null), "communicationWheel").outer),
            labels(element(fotg, "communicationWheel").outer));
    }

    @Test public void mouseButtonsCanBeHeldWithKeyboardActionsAndReleasedTogether() {
        java.util.ArrayList<String> events = new java.util.ArrayList<>();
        TouchInputState input = new TouchInputState((code, down) -> events.add(code + "=" + down));
        input.press(new int[]{2001});
        input.press(new int[]{2001, 40});
        input.release(new int[]{2001});
        input.releaseAll();
        assertEquals(List.of("2001=true", "40=true", "40=false", "2001=false"), events);
    }
}
