package com.yyba.cloud.backup;

import android.content.Context;
import android.util.Log;

import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.upload.ChunkUploader;
import com.yyba.cloud.util.PrefsManager;
import com.yyba.cloud.util.SessionManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** 手机备份引擎：接收 BackupItem（MediaStore Uri），循环调用 ChunkUploader.uploadMedia */
public class BackupEngine {

    private static final String TAG = "BackupEngine";
    private static final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    public interface Callback {
        void onProgress(int current, int total, String fileName, int percent);
        void onComplete(BackupResult result);
        void onError(String message);
    }

    private final Context context;

    public BackupEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    public static void requestCancel() {
        cancelRequested.set(true);
    }

    public static boolean wasCancelRequested() {
        return cancelRequested.get();
    }

    static void prepareRun() {
        cancelRequested.set(false);
    }

    public void backup(List<BackupItem> items, Callback callback) {
        new Thread(() -> {
            try {
                PrefsManager prefs = new PrefsManager(context);
                if (prefs.getToken() == null) {
                    if (callback != null) {
                        callback.onComplete(new BackupResult());
                    }
                    return;
                }
                ApiClient apiClient = new ApiClient(prefs);
                ApiClient.TokenValidation validation = apiClient.validateToken();
                if (validation != ApiClient.TokenValidation.VALID) {
                    if (validation == ApiClient.TokenValidation.EXPIRED) {
                        SessionManager.invalidateOnAuthFailure(context, prefs);
                    }
                    Log.w(TAG, "backup skipped: token invalid");
                    if (callback != null) {
                        callback.onComplete(new BackupResult());
                    }
                    return;
                }

                prepareRun();
                ChunkUploader uploader = new ChunkUploader(context);
                BackupResult result = new BackupResult();
                int total = items.size();

                if (callback != null && total > 0) {
                    callback.onProgress(0, total, "", 0);
                }

                for (int i = 0; i < items.size(); i++) {
                    if (shouldStop(prefs)) {
                        result.cancelled = true;
                        break;
                    }
                    BackupItem item = items.get(i);
                    final int current = i + 1;
                    if (callback != null) {
                        callback.onProgress(current, total, item.fileName, 0);
                    }
                    try {
                        long uploadResult = uploader.uploadMedia(
                                item.mediaUri,       // content://media/...
                                item.fileName,
                                item.mediaType,      // IMAGE / VIDEO / AUDIO / DOCUMENT
                                item.lastModified,
                                item.takenAt,
                                item.mediaId,        // MediaStore _ID
                                item.relativePath,
                                percent -> {
                                    if (callback != null) {
                                        callback.onProgress(current, total, item.fileName, percent);
                                    }
                                });
                        if (uploadResult < 0) {
                            String reason = uploadResult == ChunkUploader.SKIP_DUPLICATE_CONTENT
                                    ? BackupResult.REASON_DUPLICATE_CONTENT
                                    : BackupResult.REASON_ALREADY_SYNCED;
                            result.skippedItems.add(
                                    new BackupResult.ItemResult(item.fileName, reason));
                        } else {
                            result.successItems.add(
                                    new BackupResult.ItemResult(item.fileName, null));
                        }
                    } catch (Exception e) {
                        if (shouldStop(prefs)) {
                            result.cancelled = true;
                            break;
                        }
                        if (ApiClient.isAuthFailure(e)) {
                            SessionManager.invalidateOnAuthFailure(context, prefs);
                            result.cancelled = true;
                            break;
                        }
                        if (isSessionConfigError(e)) {
                            Log.d(TAG, "backup stopped: server not configured");
                            result.cancelled = true;
                            break;
                        }
                        String msg = e.getMessage() != null
                                ? e.getMessage() : e.getClass().getSimpleName();
                        result.failedItems.add(
                                new BackupResult.ItemResult(item.fileName, msg));
                        Log.e(TAG, "backup failed: " + item.fileName, e);
                    }
                }
                if (callback != null) {
                    callback.onComplete(result);
                }
            } catch (Exception e) {
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        }, "backup-engine").start();
    }

    private static boolean shouldStop(PrefsManager prefs) {
        if (cancelRequested.get()) {
            return true;
        }
        return prefs.getToken() == null || !prefs.hasServerEndpoint();
    }

    private static boolean isSessionConfigError(Exception e) {
        // 仅“服务器地址未配置”（IllegalStateException）才是致命错误，需终止整批；
        // 其他异常（网络、单文件失败等）只记入 failedItems 并继续下一个。
        return e instanceof IllegalStateException;
    }
}
