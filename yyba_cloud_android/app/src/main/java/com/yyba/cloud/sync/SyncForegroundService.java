package com.yyba.cloud.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.yyba.cloud.R;

import java.util.concurrent.atomic.AtomicBoolean;

public class SyncForegroundService extends Service {

    public static final String ACTION_SYNC = "com.yyba.cloud.SYNC";
    public static final String ACTION_CANCEL = "com.yyba.cloud.SYNC_CANCEL";
    private static final String CHANNEL_ID = "sync_channel";
    private static final int NOTIFICATION_ID = 1001;

    private static final AtomicBoolean SYNCING = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CANCEL.equals(action)) {
            SyncEngine.requestCancel();
            return START_NOT_STICKY;
        }
        if (!ACTION_SYNC.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!SYNCING.compareAndSet(false, true)) {
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification(getString(R.string.sync_running), null, 0, 0);
        startForeground(NOTIFICATION_ID, notification);
        SyncProgressBus.notifyStarted(0);

        new SyncEngine(this).syncUnsyncedMedia(new SyncEngine.Callback() {
            @Override
            public void onProgress(int current, int total, String fileName) {
                if (current == 0) {
                    SyncProgressBus.notifyStarted(total);
                }
                if (total > 0) {
                    SyncProgressBus.notifyProgress(current, total, fileName);
                    Notification n = buildNotification(
                            getString(R.string.sync_running),
                            current + "/" + total + " " + fileName,
                            total, current);
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) {
                        nm.notify(NOTIFICATION_ID, n);
                    }
                }
            }

            @Override
            public void onUploadProgress(MediaScanner.MediaItem item, int percent) {
            }

            @Override
            public void onComplete(int synced, int skipped, int failed, String lastError) {
                finishSync(synced, skipped, failed, lastError);
            }

            @Override
            public void onError(String message) {
                SyncProgressBus.notifyError(message);
                runOnMain(() -> {
                    if (message != null) {
                        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                    }
                    endSyncSession();
                });
            }
        });

        return START_NOT_STICKY;
    }

    private void finishSync(int synced, int skipped, int failed, String lastError) {
        runOnMain(() -> {
            if (SyncEngine.wasCancelRequested()) {
                SyncProgressBus.notifyCancelled(synced, skipped, failed);
                Toast.makeText(getApplicationContext(),
                        getString(R.string.sync_cancelled_summary, synced, skipped, failed),
                        Toast.LENGTH_SHORT).show();
            } else {
                SyncProgressBus.notifyCompleted(synced, skipped, failed, lastError);
                if (failed > 0 && lastError != null) {
                    Toast.makeText(getApplicationContext(), lastError, Toast.LENGTH_LONG).show();
                } else if (synced > 0 || skipped > 0) {
                    Toast.makeText(getApplicationContext(),
                            getString(R.string.sync_complete_summary, synced, skipped, failed),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(getApplicationContext(),
                            getString(R.string.sync_nothing_to_sync), Toast.LENGTH_SHORT).show();
                }
            }
            endSyncSession();
        });
    }

    private void runOnMain(Runnable action) {
        mainHandler.post(action);
    }

    private void endSyncSession() {
        SYNCING.set(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification buildNotification(String title, String text, int max, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text != null ? text : getString(R.string.sync_running))
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setOngoing(true);
        if (max > 0) {
            builder.setProgress(max, progress, false);
        }
        return builder.build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        SYNCING.set(false);
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.sync_channel),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}
