package com.fsolauncher.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Counts shared held keys and preserves modifier order across radial transitions. */
public final class TouchInputState {
    private final Map<Integer, Integer> held = new LinkedHashMap<>();
    private final BiConsumer<Integer, Boolean> output;

    public TouchInputState(BiConsumer<Integer, Boolean> output) {
        this.output = output;
    }

    public void press(int[] codes) {
        transition(Collections.emptyList(), Collections.singletonList(codes));
    }

    public void release(int[] codes) {
        transition(Collections.singletonList(codes), Collections.emptyList());
    }

    public void transition(List<int[]> released, List<int[]> pressed) {
        Map<Integer, Integer> deltas = new LinkedHashMap<>();
        for (int[] codes : released) {
            for (int i = codes.length - 1; i >= 0; i--) deltas.merge(codes[i], -1, Integer::sum);
        }
        for (int[] codes : pressed) {
            for (int code : codes) deltas.merge(code, 1, Integer::sum);
        }
        // First release removed keys. A shared key's net delta can be zero.
        for (Map.Entry<Integer, Integer> delta : deltas.entrySet()) {
            if (delta.getValue() < 0) change(delta.getKey(), delta.getValue());
        }
        for (Map.Entry<Integer, Integer> delta : deltas.entrySet()) {
            if (delta.getValue() > 0) change(delta.getKey(), delta.getValue());
        }
    }

    private void change(int code, int delta) {
        int before = held.getOrDefault(code, 0);
        int after = Math.max(0, before + delta);
        if (after == 0) held.remove(code);
        else held.put(code, after);
        if (before == 0 && after > 0) output.accept(code, true);
        else if (before > 0 && after == 0) output.accept(code, false);
    }

    public void releaseAll() {
        List<Integer> codes = new ArrayList<>(held.keySet());
        Collections.reverse(codes);
        held.clear();
        for (int code : codes) output.accept(code, false);
    }
}
