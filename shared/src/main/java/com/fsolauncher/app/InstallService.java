package com.fsolauncher.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.app.NotificationCompat;
import com.fsolauncher.core.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** User-initiated foreground transfers with visible progress and cancellation. */
public final class InstallService extends Service {
    public static volatile boolean running;
    public static volatile String message = "";
    public static volatile long completed, total;
    public static volatile PackageProgress packageProgress = new PackageProgress(0,0,null);
    public static final String CHANNEL = "game-download";
    private ExecutorService executor;
    private Future<?> future;
    private long lastNotification;
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Game downloads", NotificationManager.IMPORTANCE_LOW));
        executor = Executors.newSingleThreadExecutor();
    }
    private Notification notification() {
        Intent stop = new Intent(this, InstallService.class).setAction("cancel");
        PendingIntent cancel = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, LauncherActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder n = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(getString(com.shivansps.fsowrapper.R.string.app_name))
            .setContentText(message).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "Pause", cancel);
        PackageProgress packages = packageProgress;
        if (packages.total > 0) n.setSubText(packages.installed + "/" + packages.total + " packages")
            .setStyle(new NotificationCompat.BigTextStyle().bigText(packages.summary() + "\n" + message));
        if (total > 0) n.setProgress(1000, (int)(completed * 1000 / total), false);
        else n.setProgress(0,0,true);
        return n.build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "cancel".equals(intent.getAction())) { pause(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        running = true; message = "Checking game files"; completed = total = 0;
        packageProgress = new PackageProgress(0,0,null);
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(7, notification());
        future = executor.submit(() -> {
            try {
                Path root = GameSpec.root(this);
                try (FileChannel channel = FileChannel.open(root.resolve("game.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = channel.tryLock()) {
                    if (lock == null) throw new IOException("Close the game before updating");
                    GameSpec spec = GameSpec.load(this);
                    HttpTransport.Progress progress = new HttpTransport.Progress() {
                        @Override public void update(String text, long done, long size) {
                            message = text; completed = done; total = size;
                            updateNotification(false);
                        }
                        @Override public void packages(int installed, int count, String currentPackage) {
                            packageProgress = new PackageProgress(installed,count,currentPackage);
                            android.util.Log.i("FSOInstall",packageProgress.summary()
                                + (currentPackage == null ? "" : " · " + currentPackage));
                            updateNotification(true);
                        }
                    };
                    HttpTransport transport = new HttpTransport(progress);
                    NebulaRepository repo = new NebulaRepository(root.resolve("metadata"), transport, spec.repositoryMirrors, spec.apiMirrors);
                    Resolver.Plan plan = new Resolver(repo, spec.includeRecommended, GameSpec.abi()).resolve(spec.nebulaId, spec.contentRange);
                    ActivityManager manager = getSystemService(ActivityManager.class);
                    ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                    manager.getMemoryInfo(memory);
                    long budget = manager.isLowRamDevice() ? 128L << 20 : Math.min(1L << 30, memory.availMem / 4);
                    SevenZExtractor extractor = new NativeSevenZExtractor(Runtime.getRuntime().availableProcessors(), budget);
                    ContentInstaller installer = new ContentInstaller(root, transport, progress, extractor);
                    if (!installer.current(plan)) installer.install(plan);
                    else {
                        int count = plan.selections.stream().mapToInt(s -> s.packages.size()).sum();
                        progress.packages(count,count,null);
                    }
                    message = "Game files are up to date";
                }
            } catch (CancellationException e) { message = "Download paused. Tap Install / Update to resume."; }
            catch (Exception e) {
                message = Thread.currentThread().isInterrupted() ? "Download paused. Tap Install / Update to resume." : "Download failed: " + e.getMessage();
                android.util.Log.e("FSOLauncher", message, e);
            } finally {
                getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("last_status", message).apply();
                running = false; completed = total = 0;
                packageProgress = new PackageProgress(0,0,null);
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            }
        });
        return START_NOT_STICKY;
    }
    private void updateNotification(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (force || now - lastNotification > 600) {
            lastNotification = now;
            getSystemService(NotificationManager.class).notify(7, notification());
        }
    }
    private void pause() { if (future != null) future.cancel(true); }
    @Override public void onTimeout(int startId, int fgsType) { pause(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    @Override public void onDestroy() { pause(); executor.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
