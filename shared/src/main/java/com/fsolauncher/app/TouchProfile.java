package com.fsolauncher.app;

import android.content.Context;
import com.google.gson.Gson;
import java.io.*;
import java.util.*;

/** Packaged developer configuration; no user-editable remapping is exposed. */
public final class TouchProfile {
    public static final class Binding { public int[] codes; public String label, description; }
    public Map<String,Binding> actions = new LinkedHashMap<>();
    public Map<String,int[]> codeMap = new LinkedHashMap<>();
    public Map<String,Binding> buttons = new LinkedHashMap<>();
    public int initialHudMode = 3;
    public static TouchProfile load(Context context) {
        try (Reader reader = new InputStreamReader(context.getAssets().open("touch.json"),java.nio.charset.StandardCharsets.UTF_8)) {
            TouchProfile p = new Gson().fromJson(reader,TouchProfile.class); return p == null ? new TouchProfile() : p;
        } catch (IOException e) { return new TouchProfile(); }
    }
    public int[] map(int[] original) {
        List<Integer> result = new ArrayList<>();
        for (int code : original) {
            int[] mapped = codeMap.get(Integer.toString(code));
            if (mapped == null) result.add(code); else for (int replacement : mapped) result.add(replacement);
        }
        int[] values = new int[result.size()];
        for (int i=0;i<result.size();i++) values[i] = result.get(i);
        return values;
    }
}
