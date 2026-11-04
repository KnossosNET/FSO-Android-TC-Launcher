package com.fsolauncher.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.core.view.*;
import com.fsolauncher.core.*;
import com.shivansps.fsowrapper.GameActivity;
import com.shivansps.fsowrapper.BuildConfig;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Each installed application presents only its own game and its content updates. */
public final class LauncherActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private GameSpec spec;
    private static final int REQUEST_ENGINE_FLAGS = 201;
    private TextView status, version, engineVersion, packageStatus;
    private RadioGroup graphicsApi;
    private RadioButton esOption, vkOption;
    private CheckBox showFps;
    private String engineFullVersion;
    private boolean probingEngine;
    private Button play, update, pause;
    private ProgressBar progress;
    private Resolver.Plan available;
    private boolean checking;
    private String checkMessage = "";
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 600); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            engineFullVersion = state.getString("engine_full_version");
            probingEngine = state.getBoolean("probing_engine", false);
        }
        try {
            spec = GameSpec.load(this);
            buildUi();
            if (state == null || (engineFullVersion == null && !probingEngine))
                handler.post(this::queryEngineVersion);
        }
        catch (IOException e) { new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("Close", (d,w) -> finish()).show(); }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private Button button(LinearLayout parent, String title, View.OnClickListener action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setOnClickListener(action); parent.addView(b, new LinearLayout.LayoutParams(-1,dp(56))); return b;
    }
    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(8,14,23));
        try (InputStream in = getAssets().open("background.png")) {
            ImageView art = new ImageView(this); art.setImageDrawable(Drawable.createFromStream(in, null));
            art.setScaleType(ImageView.ScaleType.CENTER_CROP); art.setAlpha(.4f); frame.addView(art, new FrameLayout.LayoutParams(-1,-1));
        } catch (IOException ignored) { }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(28),dp(36),dp(28),dp(32)); scroll.addView(panel);
        TextView eyebrow = text("FREESPACE OPEN  /  ANDROID",12,Color.rgb(135,185,214)); panel.addView(eyebrow);
        TextView title = text(spec.displayName,34,Color.WHITE); title.setPadding(0,dp(12),0,dp(20)); panel.addView(title);
        version = text("",14,Color.LTGRAY); panel.addView(version);
        status = text("",16,Color.WHITE); status.setPadding(0,dp(20),0,dp(16)); panel.addView(status);
        packageStatus = text("",14,Color.LTGRAY); packageStatus.setVisibility(View.GONE);
        packageStatus.setPadding(0,0,0,dp(8)); panel.addView(packageStatus);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        panel.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        play = button(panel,"Play",v -> launch());
        update = button(panel,"Install game files",v -> confirmInstall());
        pause = button(panel,"Pause download",v -> startService(new Intent(this,InstallService.class).setAction("cancel")));
        button(panel,"Check for updates",v -> checkUpdates());
        TextView info = text("Game files download separately. Touch controls are included.",13,Color.LTGRAY);
        info.setPadding(0,dp(22),0,0); panel.addView(info);
        TextView graphicsLabel = text("Graphics API",14,Color.LTGRAY);
        graphicsLabel.setPadding(0,dp(20),0,dp(4)); panel.addView(graphicsLabel);
        graphicsApi = new RadioGroup(this);
        graphicsApi.setOrientation(RadioGroup.HORIZONTAL);
        esOption = new RadioButton(this); esOption.setId(View.generateViewId());
        esOption.setText("ES"); esOption.setTextColor(Color.WHITE); esOption.setContentDescription("OpenGL ES");
        vkOption = new RadioButton(this); vkOption.setId(View.generateViewId());
        vkOption.setText("VK"); vkOption.setTextColor(Color.WHITE); vkOption.setContentDescription("Vulkan");
        graphicsApi.addView(esOption, new RadioGroup.LayoutParams(0,dp(48),1f));
        graphicsApi.addView(vkOption, new RadioGroup.LayoutParams(0,dp(48),1f));
        SharedPreferences preferences = getSharedPreferences("launcher",MODE_PRIVATE);
        graphicsApi.check("es".equals(preferences.getString("graphics_api","vk")) ? esOption.getId() : vkOption.getId());
        graphicsApi.setOnCheckedChangeListener((group,id) -> preferences.edit()
            .putString("graphics_api",id == esOption.getId() ? "es" : "vk").apply());
        panel.addView(graphicsApi, new LinearLayout.LayoutParams(-1,-2));
        showFps = new CheckBox(this); showFps.setText("Show FPS"); showFps.setTextColor(Color.WHITE);
        showFps.setChecked(preferences.getBoolean("show_fps",false));
        showFps.setOnCheckedChangeListener((button,checked) -> preferences.edit().putBoolean("show_fps",checked).apply());
        panel.addView(showFps, new LinearLayout.LayoutParams(-1,dp(48)));
        TextView launcherVersion = text("Launcher Version: " + BuildConfig.LAUNCHER_VERSION
            + " / " + BuildConfig.TC_LAUNCHER_VERSION,13,Color.LTGRAY);
        launcherVersion.setPadding(0,dp(20),0,dp(6)); panel.addView(launcherVersion);
        engineVersion = text("",13,Color.LTGRAY); panel.addView(engineVersion);
        updateEngineVersionLabel();
        setContentView(frame);
        ViewCompat.setOnApplyWindowInsetsListener(frame,(view,insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom); return insets;
        });
    }
    @Override public void onResume() { super.onResume(); handler.post(ticker); }
    @Override public void onPause() { handler.removeCallbacks(ticker); super.onPause(); }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("engine_full_version",engineFullVersion);
        state.putBoolean("probing_engine",probingEngine);
        super.onSaveInstanceState(state);
    }
    private void queryEngineVersion() {
        if (isFinishing() || isDestroyed() || probingEngine) return;
        probingEngine = true;
        updateEngineVersionLabel();
        refresh();
        try { startActivityForResult(new Intent(this,FlagsActivity.class),REQUEST_ENGINE_FLAGS); }
        catch (RuntimeException e) {
            probingEngine = false;
            android.util.Log.e("FSOLauncher","Cannot query engine flags",e);
            updateEngineVersionLabel(); refresh();
        }
    }
    private void updateEngineVersionLabel() {
        if (engineVersion != null) engineVersion.setText("Engine Version: " +
            (engineFullVersion != null ? engineFullVersion : probingEngine ? "Checking…" : "Unavailable"));
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent result) {
        super.onActivityResult(requestCode,resultCode,result);
        if (requestCode != REQUEST_ENGINE_FLAGS) return;
        probingEngine = false;
        engineFullVersion = resultCode == RESULT_OK && result != null ? result.getStringExtra(FlagsActivity.EXTRA_VERSION) : null;
        updateEngineVersionLabel(); refresh();
    }
    private ContentInstaller installer() throws IOException {
        HttpTransport.Progress noop = (m,d,t) -> {};
        return new ContentInstaller(GameSpec.root(this),new HttpTransport(noop),noop);
    }
    private void refresh() {
        if (spec == null || status == null) return;
        try {
            Models.State installed = installer().state();
            boolean busy = InstallService.running;
            PackageProgress packages = InstallService.packageProgress;
            packageStatus.setVisibility(busy && packages.total > 0 ? View.VISIBLE : View.GONE);
            if (busy && packages.total > 0) packageStatus.setText(packages.summary());
            play.setEnabled(installed != null && !busy && !checking && !probingEngine);
            update.setEnabled(!busy && !checking);
            update.setText(installed == null ? "Install game files" : "Install / Update");
            pause.setVisibility(busy ? View.VISIBLE : View.GONE);
            progress.setVisibility(busy || checking ? View.VISIBLE : View.GONE);
            progress.setIndeterminate(InstallService.total <= 0);
            if (InstallService.total > 0) progress.setProgress((int)(InstallService.completed * 1000 / InstallService.total));
            version.setText(installed == null ? "Ready to install" : "Installed version " + installed.rootVersion);
            String message = busy ? InstallService.message : checking ? "Checking for updates…" :
                !checkMessage.isEmpty() ? checkMessage :
                getSharedPreferences("launcher",MODE_PRIVATE).getString("last_status",
                    installed == null ? "Download the game to begin." : "Ready to play offline.");
            status.setText(message);
        } catch (IOException e) { status.setText(e.getMessage()); play.setEnabled(false); }
    }
    private void checkUpdates() {
        if (checking || InstallService.running) return;
        checking = true; refresh();
        worker.submit(() -> {
            try {
                HttpTransport transport = new HttpTransport((m,d,t) -> {});
                NebulaRepository repo = new NebulaRepository(GameSpec.root(this).resolve("metadata"),transport,spec.repositoryMirrors,spec.apiMirrors);
                Resolver.Plan plan = new Resolver(repo,spec.includeRecommended,GameSpec.abi()).resolve(spec.nebulaId,spec.contentRange);
                boolean current = installer().current(plan);
                runOnUiThread(() -> { available = plan; checking = false; checkMessage = current ? "Game files are up to date." :
                    "Version " + plan.rootVersion + " available · " + bytes(plan.downloadBytes()) + " total content download"; refresh(); });
            } catch (Exception e) {
                runOnUiThread(() -> { checking = false; checkMessage = "Update check failed. " + e.getMessage(); refresh(); });
            }
        });
    }
    private static String bytes(long size) { return String.format(Locale.US,"%.2f GB",size / 1_000_000_000d); }
    private void confirmInstall() {
        String details = available == null ? "Download this game and its required data dependencies?" :
            "Install version " + available.rootVersion + " and its required data dependencies? Full content: " + bytes(available.downloadBytes()) + ".";
        new AlertDialog.Builder(this).setTitle(spec.displayName).setMessage(details + "\n\nUpdates may need space for both the previous and new game files.")
            .setNegativeButton("Cancel",null).setPositiveButton("Download",(d,w) -> {
                checkMessage = "";
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},101);
                androidx.core.content.ContextCompat.startForegroundService(this,new Intent(this,InstallService.class));
            }).show();
    }
    private void launch() {
        if (InstallService.running || checking || probingEngine) return;
        try {
            Models.State installed = installer().state();
            if (installed == null || !spec.nebulaId.equals(installed.rootId)) throw new IOException("Install the game first");
            ArrayList<String> args = LaunchArguments.build(spec.arguments, vkOption.isChecked(),
                showFps.isChecked(), installed.modPaths);
            Intent game = new Intent(this, GameActivity.class);
            game.putExtra("workingFolder",GameSpec.root(this).toString());
            game.putExtra("engineLibName","fso");
            game.putStringArrayListExtra("fsoArgs",args);
            startActivity(game);
        } catch (Exception e) { new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("OK",null).show(); }
    }
}
