package com.fsolauncher.core;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TouchInputStateTest {
    private final List<String> events = new ArrayList<>();
    private final TouchInputState state = new TouchInputState((code, down) -> events.add(code + "=" + down));

    @Test public void slidingBetweenPrimaryBothAndSecondaryDoesNotPulseSharedKeys() {
        state.press(new int[]{21});
        state.transition(List.of(new int[]{21}), List.of(new int[]{21, 20}));
        state.transition(List.of(new int[]{21, 20}), List.of(new int[]{20}));
        state.release(new int[]{20});
        assertEquals(List.of("21=true", "20=true", "21=false", "20=false"), events);
    }

    @Test public void anotherFingerKeepsItsSharedKeyPressed() {
        state.press(new int[]{40});
        state.press(new int[]{40});
        state.release(new int[]{40});
        assertEquals(List.of("40=true"), events);
        state.release(new int[]{40});
        state.release(new int[]{40});
        assertEquals(List.of("40=true", "40=false"), events);
    }

    @Test public void chordsPressModifiersFirstAndReleaseThemLast() {
        state.press(new int[]{1226, 1008});
        state.release(new int[]{1226, 1008});
        assertEquals(List.of("1226=true", "1008=true", "1008=false", "1226=false"), events);
    }

    @Test public void hidingControlsReleasesEveryKeyInReverseOrderAndCanBeRepeated() {
        state.press(new int[]{1226, 1008});
        state.press(new int[]{40});
        state.releaseAll();
        state.releaseAll();
        assertEquals(List.of("1226=true", "1008=true", "40=true", "40=false", "1008=false", "1226=false"), events);
        state.press(new int[0]);
        state.release(new int[0]);
        assertEquals(6, events.size());
    }
}
