package com.yyba.cloud.backup;

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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 手机备份前台服务：独立于相册同步，驱动 BackupEngine */
public class BackupForegroundService extends Service {

    public static final String ACTION_BACKUP_SYNC = "com.yyba.cloud.BACKUP_SYNC";
    public static final String ACTION_BACKUP_CANCEL = "com.yyba.cloud.BACKUP_CANCEL";
    private static final String CHANNEL_ID = "backup_channel";
    private static final int NOTIFICATION_ID = 1002;

    private static final AtomicBoolean BACKUP_SYNCING = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 静态持有待备份项：Activity 启动 Service 前设置 */
    private static volatile List<BackupItem> pendingItems;

    public static void setPendingItems(List<BackupItem> items) {
        pendingItems = items;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_BACKUP_CANCEL.equals(action)) {
            BackupEngine.requestCancel();
            return START_NOT_STICKY;
        }
        if (!ACTION_BACKUP_SYNC.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!BACKUP_SYNCING.compareAndSet(false, true)) {
            return START_NOT_STICKY;
        }

        List<BackupItem> items = pendingItems;
        pendingItems = null;
        if (items == null || items.isEmpty()) {
            BACKUP_SYNCING.set(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification(
                getString(R.string.phone_backup_running), null, 0, 0);
        startForeground(NOTIFICATION_ID, notification);
        BackupProgressBus.notifyStarted(items.size());

        new BackupEngine(this).backup(items, new BackupEngine.Callback() {
            @Override
            public void onProgress(int current, int total, String fileName, int percent) {
                if (total > 0) {
                    BackupProgressBus.notifyProgress(current, total, fileName, percent);
                    Notification n = buildNotification(
                            getString(R.string.phone_backup_running),
                            current + "/" + total + " " + fileName,
                            total, current);
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) {
                        nm.notify(NOTIFICATION_ID, n);
                    }
                }
            }

            @Override
            public void onComplete(BackupResult result) {
                finishBackup(result);
            }

            @Override
            public void onError(String message) {
                BackupProgressBus.notifyError(message);
                runOnMain(() -> {
                    if (message != null) {
                        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                    }
                    endBackupSession();
                });
            }
        });

        return START_NOT_STICKY;
    }

    private void finishBackup(BackupResult result) {
        runOnMain(() -> {
            int synced = result.getSuccessCount();
            int skipped = result.getSkippedCount();
            int failed = result.getFailedCount();
            int duplicates = countDuplicates(result);

            if (result.cancelled) {
                BackupProgressBus.notifyCancelled(synced, skipped, failed);
                Toast.makeText(getApplicationContext(),
                        getString(R.string.phone_backup_cancelled_summary, synced, skipped, failed),
                        Toast.LENGTH_SHORT).show();
            } else {
                String msg = failed > 0
                        ? getString(R.string.phone_backup_failed_summary, synced, skipped, failed)
                        : getString(R.string.phone_backup_complete_summary, synced, skipped, failed);
                int duration = Toast.LENGTH_SHORT;
                if (duplicates > 0) {
                    msg = msg + "\n" + getString(R.string.phone_backup_duplicate_hint, duplicates);
                    duration = Toast.LENGTH_LONG;
                }
                BackupProgressBus.notifyCompleted(synced, skipped, failed, msg);
                Toast.makeText(getApplicationContext(), msg, duration).show();
            }
            endBackupSession();
        });
    }

    private static int countDuplicates(BackupResult result) {
        int count = 0;
        for (BackupResult.ItemResult item : result.skippedItems) {
            if (BackupResult.REASON_DUPLICATE_CONTENT.equals(item.reason)) {
                count++;
            }
        }
        return count;
    }

    private void runOnMain(Runnable action) {
        mainHandler.post(action);
    }

    private void endBackupSession() {
        BACKUP_SYNCING.set(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification buildNotification(String title, String text, int max, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text != null ? text : getString(R.string.phone_backup_running))
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
        BACKUP_SYNCING.set(false);
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.phone_backup_channel),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }
}
