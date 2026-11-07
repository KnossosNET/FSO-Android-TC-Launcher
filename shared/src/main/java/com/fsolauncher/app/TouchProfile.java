package com.fsolauncher.app;

import android.content.Context;
import android.util.Log;
import com.fsolauncher.core.TouchLayout;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Loads the packaged shared layout and an optional TC layout or legacy remapping. */
public final class TouchProfile {
    private TouchProfile() {}

    public static TouchLayout load(Context context) {
        final String shared;
        try {
            shared = read(context, "shared-touch-default.json");
        } catch (IOException e) {
            throw new IllegalStateException("Missing packaged shared touch layout", e);
        }
        String module = null;
        try {
            module = read(context, "touch.json");
        } catch (FileNotFoundException ignored) {
            // A TC can use the shared layout without a module profile.
        } catch (IOException e) {
            Log.w("FSOTouch", "Cannot read the TC touch profile", e);
        }
        try {
            return TouchLayout.parse(shared, module);
        } catch (RuntimeException e) {
            Log.w("FSOTouch", "Invalid TC touch profile. Using the shared layout", e);
            return TouchLayout.parse(shared, null);
        }
    }

    private static String read(Context context, String asset) throws IOException {
        try (InputStream input = context.getAssets().open(asset)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
