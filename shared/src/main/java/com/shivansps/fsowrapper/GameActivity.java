package com.shivansps.fsowrapper;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import com.shivansps.fsowrapper.overlay.HudStyle;
import com.shivansps.fsowrapper.overlay.TouchOverlay;
import com.fsolauncher.core.TouchInputState;
import com.fsolauncher.core.TouchLayout;
import com.shivansps.fsowrapper.tts.TTSManager;
import java.util.ArrayList;
import android.view.*;
import android.widget.*;
import com.shivansps.fsowrapper.overlay.NativeBridge;
import java.lang.ref.WeakReference;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class GameActivity extends org.libsdl.app.SDLActivity {

    private static String _workingFolder = "";
    private static WeakReference<View> _overlayRef = null;
    private static WeakReference<GameActivity> _activityRef = null;
    private static Boolean _pendingVisibility = null;
    private static Boolean _forceOverlayOn = false;

    private final TouchInputState touchInput = new TouchInputState(
        (code, pressed) -> NativeBridge.onButton(code, pressed));
    private TouchOverlay touchOverlay;
    private TouchLayout touchProfile;
    private java.nio.channels.FileChannel gameLockChannel;
    private java.nio.channels.FileLock gameLock;

    /* FSO API */

    public static String getWorkingFolder() { return _workingFolder; }

    public static void setOverlayOpacity(float opacity) {
        HudStyle.setBackgroundOpacity(opacity);
        GameActivity activity = _activityRef != null ? _activityRef.get() : null;
        View overlay = _overlayRef != null ? _overlayRef.get() : null;
        if (activity != null && overlay != null) {
            overlay.post(activity::refreshHudAppearance);
        }
    }

    public static void enableOverlay() {
        if (_forceOverlayOn) return;
        View overlay = _overlayRef != null ? _overlayRef.get() : null;
        if (overlay != null) {
            overlay.post(() -> overlay.setVisibility(View.VISIBLE));
        } else {
            _pendingVisibility = true;
        }
    }

    public static void disableOverlay() {
        if (_forceOverlayOn) return;
        View overlay = _overlayRef != null ? _overlayRef.get() : null;
        if (overlay != null) {
            GameActivity activity = _activityRef != null ? _activityRef.get() : null;
            overlay.post(() -> {
                if (activity != null) activity.releaseOverlayInputs();
                overlay.setVisibility(View.GONE);
            });
        } else {
            _pendingVisibility = false;
        }
    }

    // TTS wrappers ----------------------------------------------------------
    public static boolean tts_speak(String text)     { return TTSManager.speak(text); }
    public static boolean tts_stop()                 { return TTSManager.stop(); }
    public static boolean tts_pause()                { return TTSManager.pause(); }
    public static boolean tts_resume()               { return TTSManager.resume(); }
    public static boolean tts_isSpeaking()           { return TTSManager.isSpeaking(); }
    public static void    tts_shutdown()             { TTSManager.shutdown(); }
    public static void    tts_setRate(float rate)    { TTSManager.setRate(rate); }
    public static void    tts_setLanguageTag(String tag) { TTSManager.setLanguageTag(tag); }
    public static String[] tts_getAvailableLanguageTags() { return TTSManager.getAvailableLanguageTags(); }
    // -------------------------------------------------------------------------

    /* ******* */

    @Override
    protected String[] getArguments() {
        android.content.Intent i = getIntent();
        java.util.ArrayList<String> args = (i != null)
                ? i.getStringArrayListExtra("fsoArgs")
                : null;

        if (args == null || args.isEmpty()) {
            return new String[0];
        }
        return args.toArray(new String[0]);
    }

    @Override
    protected String[] getLibraries() {
        ArrayList<String> libs = new ArrayList<>();
        libs.add("SDL3");

        Intent i = getIntent();
        String engineBase = (i != null) ? i.getStringExtra("engineLibName") : null;
        if (engineBase == null || engineBase.trim().isEmpty()) {
            engineBase = "fso"; // The APK always owns the engine binary.
        }
        libs.add(engineBase);
        return libs.toArray(new String[0]);
    }

    @Override
    protected String getMainFunction() {
        return "android_main";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            getWindow().setSustainedPerformanceMode(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            applyImmersive();
        } catch (Throwable ignored) {
        }
        // FSO can query this during SDL initialization, so set it before super.onCreate.
        Intent launch = getIntent();
        _workingFolder = launch != null ? launch.getStringExtra("workingFolder") : "";
        touchProfile = com.fsolauncher.app.TouchProfile.load(this);
        try {
            java.nio.file.Path lock = java.nio.file.Paths.get(_workingFolder, "game.lock");
            gameLockChannel = java.nio.channels.FileChannel.open(lock,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            gameLock = gameLockChannel.tryLock();
            if (gameLock == null) throw new java.io.IOException("An update is active");
        } catch (java.io.IOException e) { throw new IllegalStateException("Game storage is busy", e); }
        TTSManager.init(this);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                    setKeyPressed(NativeBridge.CODE_ESC, true);
                    getWindow().getDecorView().postDelayed(
                        () -> setKeyPressed(NativeBridge.CODE_ESC, false), 45L);
                });
        }
        _activityRef = new WeakReference<>(this);
        Intent i = getIntent();
        if(i != null)
        {
            _forceOverlayOn = i.getBooleanExtra("forceTouchOverlay", false);
            if(_forceOverlayOn) HudStyle.ensureVisible();
            _workingFolder = i.getStringExtra("workingFolder");
            getWindow().getDecorView().post(this::setupOverlay);
        }

    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_ESCAPE || k == KeyEvent.KEYCODE_BACK) {
            switch (e.getAction()) {
                case KeyEvent.ACTION_DOWN:
                    if (e.getRepeatCount() == 0) {
                        setKeyPressed(NativeBridge.CODE_ESC, true);
                    }
                    return true;
                case KeyEvent.ACTION_UP:
                    setKeyPressed(NativeBridge.CODE_ESC, false);
                    return true;
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onPause() {
        releaseOverlayInputs();
        TTSManager.stop();
        super.onPause();
    }

    private void applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        applyImmersive();
    }

    @Override protected void onDestroy()
    {
        _workingFolder = "";
        _overlayRef  = null;
        _activityRef = null;
        _pendingVisibility = null;
        TTSManager.shutdown();
        super.onDestroy();
        try {
            if (gameLock != null) gameLock.release();
            if (gameLockChannel != null) gameLockChannel.close();
        } catch (java.io.IOException ignored) { }
        try {
            if (isChangingConfigurations()) return;
            // kill process if it is still running
            // Note: may break pilot files
            String proc = android.app.Application.getProcessName();
            if (proc != null && proc.endsWith(":game")) {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        } catch (Throwable ignored) {}
    }

    private void toggleSdlKeyboard(View overlayRoot) {
        boolean imeVisible = false;

        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = overlayRoot.getRootWindowInsets();
            if (insets != null) {
                imeVisible = insets.isVisible(WindowInsets.Type.ime());
            }
        }

        if (!imeVisible) {
            NativeBridge.setTextInputEnabled(true);
        } else {
            NativeBridge.setTextInputEnabled(false);
        }
    }

    private void setKeyPressed(int code, boolean pressed) {
        if (pressed) touchInput.press(new int[]{code});
        else touchInput.release(new int[]{code});
    }

    private void releaseOverlayInputs() {
        if (touchOverlay != null) touchOverlay.releaseInputs();
        else touchInput.releaseAll();
    }

    private void refreshHudAppearance() {
        if (touchOverlay != null) touchOverlay.refreshAppearance();
    }

    private void setupOverlay() {
        touchOverlay = new TouchOverlay(this, touchProfile, touchInput,
            () -> toggleSdlKeyboard(getWindow().getDecorView()));
        View overlay = touchOverlay;
        ViewCompat.setOnApplyWindowInsetsListener(overlay, (view, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.systemGestures());
            view.setPadding(safe.left, 0, safe.right, safe.bottom);
            return windowInsets;
        });
        addContentView(overlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ViewCompat.requestApplyInsets(overlay);
        overlay.bringToFront();
        overlay.setElevation(10000f);
        _overlayRef = new WeakReference<>(overlay);
        if (!_forceOverlayOn) overlay.setVisibility(View.GONE);
        if (_pendingVisibility != null && !_forceOverlayOn) {
            overlay.setVisibility(_pendingVisibility ? View.VISIBLE : View.GONE);
        }
        _pendingVisibility = null;
    }
}
