package com.yyba.cloud.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.api.SyncRecordDto;
import com.yyba.cloud.api.VerifySyncItem;
import com.yyba.cloud.api.VerifySyncResponse;
import com.yyba.cloud.api.VerifySyncValidItem;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.db.SyncRecordDao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 手机 Tab verify-sync：分页懒重建 sync_records + 校正已有 remoteFileId。
 */
public final class SyncVerifyScheduler {

    public static final int PAGE_SIZE = 50;
    private static final long DEBOUNCE_MS = 300L;
    private static final long WINDOW_INTERVAL_MS = 10 * 60 * 1000L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sync-verify");
        t.setDaemon(true);
        return t;
    });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> lastCheckByWindow = new ConcurrentHashMap<>();
    private final Set<String> inFlightWindows = ConcurrentHashMap.newKeySet();
    private Runnable pendingRunnable;

    public interface Callback {
        void onVerifyComplete(VerifyResult result);
    }

    public static final class Target {
        public final int adapterPosition;
        public final String stableKey;
        public final int mediaStoreOffset;
        public final long remoteFileId;
        public final String localUri;
        public final String relativePath;
        public final long fileSize;
        public final long capturedAt;
        public final String fileName;
        public final String mediaType;
        public final long mediaId;
        public final String fileHash;

        public Target(int adapterPosition, String stableKey, int mediaStoreOffset, long remoteFileId,
                      String localUri, String relativePath, long fileSize, long capturedAt,
                      String fileName, String mediaType, long mediaId, String fileHash) {
            this.adapterPosition = adapterPosition;
            this.stableKey = stableKey;
            this.mediaStoreOffset = mediaStoreOffset;
            this.remoteFileId = remoteFileId;
            this.localUri = localUri;
            this.relativePath = relativePath;
            this.fileSize = fileSize;
            this.capturedAt = capturedAt;
            this.fileName = fileName;
            this.mediaType = mediaType;
            this.mediaId = mediaId;
            this.fileHash = fileHash;
        }

        VerifySyncItem toRequestItem() {
            VerifySyncItem item = new VerifySyncItem();
            if (remoteFileId > 0) {
                item.remoteFileId = remoteFileId;
                return item;
            }
            item.localUri = localUri;
            item.relativePath = relativePath;
            item.fileSize = fileSize > 0 ? fileSize : null;
            item.capturedAt = capturedAt > 0 ? capturedAt : null;
            item.fileName = fileName;
            item.mediaType = mediaType;
            item.mediaId = mediaId > 0 ? mediaId : null;
            if (fileHash != null && !fileHash.isEmpty()) {
                item.fileHash = fileHash;
            }
            return item;
        }
    }

    public static final class VerifyResult {
        public final List<Integer> invalidatedPositions;
        public final List<String> invalidatedKeys;
        public final List<Integer> rebuiltPositions;
        public final List<String> rebuiltKeys;
        public final Map<String, Integer> shareByPath;
        public final List<SyncRecord> rebuiltRecords;

        public VerifyResult(List<Integer> invalidatedPositions,
                            List<String> invalidatedKeys,
                            List<Integer> rebuiltPositions,
                            List<String> rebuiltKeys,
                            Map<String, Integer> shareByPath,
                            List<SyncRecord> rebuiltRecords) {
            this.invalidatedPositions = invalidatedPositions;
            this.invalidatedKeys = invalidatedKeys;
            this.rebuiltPositions = rebuiltPositions;
            this.rebuiltKeys = rebuiltKeys;
            this.shareByPath = shareByPath;
            this.rebuiltRecords = rebuiltRecords;
        }
    }

    public void schedule(Context context, String category, List<Target> visibleTargets, ApiClient api,
                         Callback callback) {
        if (context == null || visibleTargets == null || visibleTargets.isEmpty() || callback == null) {
            return;
        }
        Context app = context.getApplicationContext();
        if (!NetworkHelper.hasValidatedNetwork(app)) {
            return;
        }
        if (pendingRunnable != null) {
            mainHandler.removeCallbacks(pendingRunnable);
        }
        pendingRunnable = () -> runVerify(app, category, visibleTargets, api, callback);
        mainHandler.postDelayed(pendingRunnable, DEBOUNCE_MS);
    }

    public void cancel() {
        if (pendingRunnable != null) {
            mainHandler.removeCallbacks(pendingRunnable);
            pendingRunnable = null;
        }
    }

    /** 下拉刷新：立即校验，不走防抖/窗口频控 */
    public void verifyNow(Context context, List<Target> targets, ApiClient api, Callback callback) {
        cancel();
        if (context == null || targets == null || targets.isEmpty() || callback == null) {
            if (callback != null) {
                callback.onVerifyComplete(new VerifyResult(List.of(), List.of(), List.of(), List.of(), Map.of(), List.of()));
            }
            return;
        }
        Context app = context.getApplicationContext();
        if (!NetworkHelper.hasValidatedNetwork(app)) {
            callback.onVerifyComplete(emptyResult());
            return;
        }
        EXECUTOR.execute(() -> {
            VerifyResult result;
            try {
                result = executeVerify(app, targets, api);
            } catch (Exception e) {
                result = emptyResult();
            }
            VerifyResult finalResult = result;
            mainHandler.post(() -> callback.onVerifyComplete(finalResult));
        });
    }

    /** 校正本地有 fileHash 但缺 remoteFileId 的历史记录（如下载 bug 遗留） */
    public void verifyRecords(Context context, List<SyncRecord> records, ApiClient api, Callback callback) {
        cancel();
        if (context == null || records == null || records.isEmpty() || callback == null) {
            if (callback != null) {
                callback.onVerifyComplete(emptyResult());
            }
            return;
        }
        Context app = context.getApplicationContext();
        if (!NetworkHelper.hasValidatedNetwork(app)) {
            callback.onVerifyComplete(emptyResult());
            return;
        }
        EXECUTOR.execute(() -> {
            VerifyResult result;
            try {
                result = executeVerifyRecords(app, records, api);
            } catch (Exception e) {
                result = emptyResult();
            }
            VerifyResult finalResult = result;
            mainHandler.post(() -> callback.onVerifyComplete(finalResult));
        });
    }

    static VerifySyncItem recordToVerifyItem(SyncRecord record) {
        if (record == null || record.localUri == null || record.localUri.isEmpty()) {
            return null;
        }
        if (record.remoteFileId != null && record.remoteFileId > 0) {
            VerifySyncItem item = new VerifySyncItem();
            item.remoteFileId = record.remoteFileId;
            return item;
        }
        VerifySyncItem item = new VerifySyncItem();
        item.localUri = record.localUri;
        String path = record.remotePath;
        if (path != null && !path.isEmpty()) {
            item.relativePath = path;
        }
        if (record.fileSize > 0) {
            item.fileSize = record.fileSize;
        }
        if (record.modifiedAt > 0) {
            item.capturedAt = record.modifiedAt;
        }
        item.fileName = record.fileName;
        item.mediaType = record.mediaType;
        if (record.mediaId != null && record.mediaId > 0) {
            item.mediaId = record.mediaId;
        }
        if (record.fileHash != null && !record.fileHash.isEmpty()) {
            item.fileHash = record.fileHash;
        }
        return item;
    }

    private static VerifyResult executeVerifyRecords(Context app, List<SyncRecord> records, ApiClient api)
            throws Exception {
        Map<Long, Target> fileIdToTarget = new HashMap<>();
        Map<String, Target> localUriToTarget = new HashMap<>();
        List<VerifySyncItem> items = new ArrayList<>();
        for (SyncRecord record : records) {
            VerifySyncItem item = recordToVerifyItem(record);
            if (item == null) {
                continue;
            }
            items.add(item);
            if (record.localUri != null) {
                localUriToTarget.putIfAbsent(record.localUri, targetFromRecord(record));
            }
            if (record.remoteFileId != null && record.remoteFileId > 0) {
                fileIdToTarget.putIfAbsent(record.remoteFileId, targetFromRecord(record));
            }
        }
        if (items.isEmpty()) {
            return emptyResult();
        }
        VerifyResult merged = new VerifyResult(new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new ArrayList<>());
        for (int offset = 0; offset < items.size(); offset += PAGE_SIZE) {
            int end = Math.min(offset + PAGE_SIZE, items.size());
            List<VerifySyncItem> chunk = items.subList(offset, end);
            VerifySyncResponse response = api.verifySyncItems(chunk);
            VerifyResult part = applyResponse(app, fileIdToTarget, localUriToTarget, response);
            merged.invalidatedPositions.addAll(part.invalidatedPositions);
            merged.invalidatedKeys.addAll(part.invalidatedKeys);
            merged.rebuiltPositions.addAll(part.rebuiltPositions);
            merged.rebuiltKeys.addAll(part.rebuiltKeys);
            merged.shareByPath.putAll(part.shareByPath);
            merged.rebuiltRecords.addAll(part.rebuiltRecords);
        }
        return merged;
    }

    private static Target targetFromRecord(SyncRecord record) {
        long mediaId = record.mediaId != null ? record.mediaId : 0L;
        long remoteFileId = record.remoteFileId != null ? record.remoteFileId : 0L;
        String path = record.remotePath != null ? record.remotePath : "";
        return new Target(-1, null, -1, remoteFileId, record.localUri, path,
                record.fileSize, record.modifiedAt, record.fileName, record.mediaType, mediaId,
                record.fileHash);
    }

    private void runVerify(Context app, String category, List<Target> visibleTargets, ApiClient api,
                           Callback callback) {
        long now = System.currentTimeMillis();
        Map<Long, Target> fileIdToTarget = new HashMap<>();
        Map<String, Target> localUriToTarget = new HashMap<>();
        Set<String> windowsToCheck = new HashSet<>();
        List<Target> batch = new ArrayList<>();
        for (Target target : visibleTargets) {
            if (target.localUri == null || target.localUri.isEmpty()) {
                continue;
            }
            String windowKey = windowKey(category, target.mediaStoreOffset);
            Long last = lastCheckByWindow.get(windowKey);
            if (last != null && now - last < WINDOW_INTERVAL_MS) {
                continue;
            }
            if (!inFlightWindows.add(windowKey)) {
                continue;
            }
            windowsToCheck.add(windowKey);
            batch.add(target);
            localUriToTarget.putIfAbsent(target.localUri, target);
            if (target.remoteFileId > 0) {
                fileIdToTarget.putIfAbsent(target.remoteFileId, target);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        List<String> windowKeys = new ArrayList<>(windowsToCheck);
        EXECUTOR.execute(() -> {
            VerifyResult result;
            try {
                result = executeVerify(app, batch, api);
                long ts = System.currentTimeMillis();
                for (String key : windowKeys) {
                    lastCheckByWindow.put(key, ts);
                }
            } catch (Exception e) {
                result = emptyResult();
            } finally {
                for (String key : windowKeys) {
                    inFlightWindows.remove(key);
                }
            }
            VerifyResult finalResult = result;
            mainHandler.post(() -> callback.onVerifyComplete(finalResult));
        });
    }

    private static VerifyResult executeVerify(Context app, List<Target> targets, ApiClient api)
            throws Exception {
        Map<Long, Target> fileIdToTarget = new HashMap<>();
        Map<String, Target> localUriToTarget = new HashMap<>();
        for (Target target : targets) {
            if (target.localUri != null) {
                localUriToTarget.putIfAbsent(target.localUri, target);
            }
            if (target.remoteFileId > 0) {
                fileIdToTarget.putIfAbsent(target.remoteFileId, target);
            }
        }
        List<VerifySyncItem> items = new ArrayList<>();
        for (Target target : targets) {
            items.add(target.toRequestItem());
        }
        VerifyResult merged = new VerifyResult(new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new ArrayList<>());
        for (int offset = 0; offset < items.size(); offset += PAGE_SIZE) {
            int end = Math.min(offset + PAGE_SIZE, items.size());
            List<VerifySyncItem> chunk = items.subList(offset, end);
            VerifySyncResponse response = api.verifySyncItems(chunk);
            VerifyResult part = applyResponse(app, fileIdToTarget, localUriToTarget, response);
            merged.invalidatedPositions.addAll(part.invalidatedPositions);
            merged.invalidatedKeys.addAll(part.invalidatedKeys);
            merged.rebuiltPositions.addAll(part.rebuiltPositions);
            merged.rebuiltKeys.addAll(part.rebuiltKeys);
            merged.shareByPath.putAll(part.shareByPath);
            merged.rebuiltRecords.addAll(part.rebuiltRecords);
        }
        return merged;
    }

    private static VerifyResult applyResponse(Context app, Map<Long, Target> fileIdToTarget,
                                              Map<String, Target> localUriToTarget,
                                              VerifySyncResponse response) {
        if (response == null) {
            return emptyResult();
        }
        Set<Long> invalid = new HashSet<>();
        if (response.invalidIds != null) {
            invalid.addAll(response.invalidIds);
        }
        Map<String, Integer> shareByPath = new HashMap<>();
        if (response.validItems != null) {
            for (VerifySyncValidItem item : response.validItems) {
                if (item.relativePath != null && item.share != null) {
                    shareByPath.put(item.relativePath, item.share);
                }
            }
        }
        List<Integer> invalidatedPositions = new ArrayList<>();
        List<String> invalidatedKeys = new ArrayList<>();
        List<Integer> rebuiltPositions = new ArrayList<>();
        List<String> rebuiltKeys = new ArrayList<>();
        List<SyncRecord> rebuiltRecords = new ArrayList<>();
        SyncRecordDao dao = AppDatabase.getInstance(app).syncRecordDao();
        if (!invalid.isEmpty()) {
            for (Long id : invalid) {
                dao.clearRemoteByFileId(id);
                Target target = fileIdToTarget.get(id);
                if (target != null) {
                    invalidatedPositions.add(target.adapterPosition);
                    if (target.stableKey != null) {
                        invalidatedKeys.add(target.stableKey);
                    }
                }
            }
        }
        if (response.rebuiltRecords != null && !response.rebuiltRecords.isEmpty()) {
            long now = System.currentTimeMillis();
            List<SyncRecord> toInsert = new ArrayList<>();
            for (SyncRecordDto dto : response.rebuiltRecords) {
                if (dto == null || dto.localUri == null || dto.localUri.isEmpty()) {
                    continue;
                }
                if (dto.remoteFileId == null || dto.remoteFileId <= 0) {
                    continue;
                }
                SyncRecord record = new SyncRecord();
                record.localUri = dto.localUri;
                record.mediaId = dto.mediaId;
                record.fileName = dto.fileName;
                record.fileSize = dto.fileSize != null ? dto.fileSize : 0L;
                record.fileHash = dto.fileHash;
                record.mediaType = dto.mediaType;
                record.modifiedAt = dto.modifiedAt != null ? dto.modifiedAt : 0L;
                record.remotePath = dto.remotePath;
                record.remoteFileId = dto.remoteFileId;
                record.syncedAt = now;
                toInsert.add(record);
                rebuiltRecords.add(record);
                Target target = localUriToTarget.get(dto.localUri);
                if (target != null) {
                    rebuiltPositions.add(target.adapterPosition);
                    if (target.stableKey != null) {
                        rebuiltKeys.add(target.stableKey);
                    }
                    if (dto.remotePath != null && dto.share != null) {
                        shareByPath.put(dto.remotePath, dto.share);
                    }
                }
            }
            if (!toInsert.isEmpty()) {
                for (SyncRecord record : toInsert) {
                    if (record.localUri != null) {
                        dao.deleteIncompleteByLocalUri(record.localUri);
                    }
                }
                dao.insertAll(toInsert);
            }
        }
        return new VerifyResult(invalidatedPositions, invalidatedKeys, rebuiltPositions, rebuiltKeys,
                shareByPath, rebuiltRecords);
    }

    private static VerifyResult emptyResult() {
        return new VerifyResult(List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());
    }

    private static String windowKey(String category, int mediaStoreOffset) {
        int windowStart = Math.max(0, mediaStoreOffset / PAGE_SIZE * PAGE_SIZE);
        return (category == null ? "IMAGE" : category) + ":" + windowStart;
    }
}
