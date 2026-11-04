package com.fsolauncher.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Keep;
import com.fsolauncher.core.EngineFlags;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/**
 * Adapted from Knossos.NET's FlagsActivity. The engine calls setFlagsJson through JNI.
 * A separate short-lived process isolates SDL initialization from actual gameplay.
 */
@Keep
public final class FlagsActivity extends SDLActivity {
    public static final String EXTRA_VERSION = "engineVersionFull";
    private static WeakReference<FlagsActivity> self;
    private static String workingFolder = "";
    private final AtomicBoolean delivered = new AtomicBoolean();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> complete(null);

    // These public static methods form the engine's Java callback API.
    public static String getWorkingFolder() { return workingFolder; }
    public static void setFlagsJson(String json) {
        FlagsActivity activity = self == null ? null : self.get();
        if (activity == null) { Log.w("FSOFlags","Flags callback has no activity"); return; }
        Log.i("FSOFlags","Received flags JSON (" + (json == null ? 0 : json.length()) + " characters)");
        try { activity.complete(EngineFlags.fullVersion(json)); }
        catch (IOException e) { Log.e("FSOFlags", "Cannot read version.full", e); activity.complete(null); }
    }
    public static void setOverlayOpacity(float opacity) {}
    public static void enableOverlay() {}
    public static void disableOverlay() {}

    @Override protected String[] getArguments() { return new String[]{"-get_flags", "json_v2"}; }
    @Override protected String[] getLibraries() { return new String[]{"SDL3", "fso"}; }
    @Override protected String getMainFunction() { return "android_main"; }

    @Override protected SDLSurface createSDLSurface(Context context) {
        SDLSurface surface = super.createSDLSurface(context);
        // Keep a valid SDL surface for engine startup without obscuring the launcher.
        surface.setAlpha(0f);
        return surface;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        self = new WeakReference<>(this);
        workingFolder = getFilesDir().getAbsolutePath();
        setResult(Activity.RESULT_CANCELED);
        handler.postDelayed(timeout, 30000);
        super.onCreate(savedInstanceState);
        if (SDLActivity.mBrokenLibraries) complete(null);
    }

    private void complete(String version) {
        if (!delivered.compareAndSet(false, true)) return;
        // SDL finishes this activity directly on its native thread as soon as main returns.
        // Store the result before returning from the JNI callback, not in a later UI task.
        if (version != null) {
            setResult(Activity.RESULT_OK, new Intent().putExtra(EXTRA_VERSION, version));
            Log.i("FSOFlags","Engine version.full=" + version);
        } else Log.w("FSOFlags","Engine version query did not return a valid version");
        handler.post(() -> {
            handler.removeCallbacks(timeout);
            finish();
        });
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
        self = null;
        workingFolder = "";
        // FSO/SDL may exit or keep native state alive after a flags request.
        // Only this isolated process is terminated; the launcher and game survive.
        String process = android.app.Application.getProcessName();
        if (process != null && process.endsWith(":flags"))
            android.os.Process.killProcess(android.os.Process.myPid());
    }
}
