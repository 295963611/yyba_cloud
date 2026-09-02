package com.yyba.cloud.sync;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.upload.ChunkUploader;
import com.yyba.cloud.util.PrefsManager;
import com.yyba.cloud.util.SessionManager;
import com.yyba.cloud.util.SyncRecordLookup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SyncEngine {

    private static final String TAG = "SyncEngine";
    private static final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    public interface Callback {
        void onProgress(int current, int total, String fileName);
        void onUploadProgress(MediaScanner.MediaItem item, int percent);
        void onComplete(int synced, int skipped, int failed, String lastError);
        void onError(String message);
    }

    @FunctionalInterface
    private interface MediaItemSupplier {
        List<MediaScanner.MediaItem> get() throws Exception;
    }

    private final Context context;

    public SyncEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 登录页等场景：停止排队/进行中的后台同步，避免未登录时刷屏报错。 */
    public static void haltBackgroundSync(Context context) {
        cancelRequested.set(true);
        Context app = context.getApplicationContext();
        SyncWorker.cancel(app);
        app.stopService(new Intent(app, SyncForegroundService.class));
    }

    static void prepareRun() {
        cancelRequested.set(false);
    }

    public static void requestCancel() {
        cancelRequested.set(true);
    }

    public static boolean wasCancelRequested() {
        return cancelRequested.get();
    }

    public void sync(Callback callback) {
        syncItems(() -> new MediaScanner().scanAll(context), callback);
    }

    /** 立即同步：仅上传未同步的照片、视频、音频 */
    public void syncUnsyncedMedia(Callback callback) {
        syncItems(() -> {
            Map<String, SyncRecord> index = SyncRecordLookup.indexByLocalUri(
                    AppDatabase.getInstance(context).syncRecordDao().findAll());
            MediaScanner scanner = new MediaScanner();
            List<MediaScanner.MediaItem> items = new ArrayList<>();
            items.addAll(scanner.collectUnsynced(context, "IMAGE", index));
            items.addAll(scanner.collectUnsynced(context, "VIDEO", index));
            items.addAll(scanner.collectUnsynced(context, "AUDIO", index));
            return items;
        }, callback);
    }

    public void syncCategory(String category, Callback callback) {
        syncItems(() -> new MediaScanner().scanByCategory(context, category), callback);
    }

    public void syncSingle(MediaScanner.MediaItem item, Callback callback) {
        syncItems(() -> Collections.singletonList(item), callback);
    }

    private void syncItems(MediaItemSupplier supplier, Callback callback) {
        new Thread(() -> {
            try {
                PrefsManager prefs = new PrefsManager(context);
                if (prefs.getToken() == null) {
                    if (callback != null) {
                        callback.onComplete(0, 0, 0, null);
                    }
                    return;
                }
                ApiClient apiClient = new ApiClient(prefs);
                ApiClient.TokenValidation validation = apiClient.validateToken();
                if (validation != ApiClient.TokenValidation.VALID) {
                    if (validation == ApiClient.TokenValidation.EXPIRED) {
                        SessionManager.invalidateOnAuthFailure(context, prefs);
                    }
                    Log.w(TAG, "sync skipped: token invalid");
                    if (callback != null) {
                        callback.onComplete(0, 0, 0, "登录已失效，请重新登录");
                    }
                    return;
                }

                prepareRun();
                List<MediaScanner.MediaItem> items = supplier.get();
                ChunkUploader uploader = new ChunkUploader(context);
                int total = items.size();
                if (callback != null && total > 0) {
                    callback.onProgress(0, total, "");
                }
                AtomicInteger synced = new AtomicInteger();
                AtomicInteger skipped = new AtomicInteger();
                AtomicInteger failed = new AtomicInteger();
                AtomicReference<String> lastError = new AtomicReference<>();

                for (int i = 0; i < items.size(); i++) {
                    if (shouldStopSync(prefs)) {
                        lastError.set("同步已取消");
                        break;
                    }
                    MediaScanner.MediaItem item = items.get(i);
                    if (callback != null) {
                        callback.onProgress(i + 1, total, item.fileName);
                    }
                    try {
                        long result = uploader.uploadMedia(
                                item.uri, item.fileName, item.mediaType,
                                item.modifiedAt, item.takenAt, item.mediaId, item.relativePath,
                                percent -> {
                                    if (callback != null) {
                                        callback.onUploadProgress(item, percent);
                                    }
                                });
                        if (result < 0) {
                            skipped.incrementAndGet();
                        } else {
                            synced.incrementAndGet();
                        }
                    } catch (Exception e) {
                        if (shouldStopSync(prefs)) {
                            Log.d(TAG, "sync stopped: session ended");
                            break;
                        }
                        if (ApiClient.isAuthFailure(e)) {
                            SessionManager.invalidateOnAuthFailure(context, prefs);
                            lastError.set(e.getMessage());
                            Log.w(TAG, "sync aborted: auth failure");
                            break;
                        }
                        if (isSessionConfigError(e)) {
                            Log.d(TAG, "sync stopped: server not configured");
                            break;
                        }
                        failed.incrementAndGet();
                        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        lastError.set(msg);
                        Log.e(TAG, "upload failed: " + item.fileName, e);
                    }
                }
                if (callback != null) {
                    callback.onComplete(synced.get(), skipped.get(), failed.get(), lastError.get());
                }
            } catch (Exception e) {
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        }, "sync-engine").start();
    }

    private static boolean shouldStopSync(PrefsManager prefs) {
        if (cancelRequested.get()) {
            return true;
        }
        return prefs.getToken() == null || !prefs.hasServerEndpoint();
    }

    private static boolean isSessionConfigError(Exception e) {
        if (!(e instanceof IllegalStateException)) {
            return false;
        }
        String msg = e.getMessage();
        return msg != null && msg.contains("服务器地址未配置");
    }
}
