package com.yyba.cloud.backup;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.api.SyncRecordDto;
import com.yyba.cloud.api.VerifySyncItem;
import com.yyba.cloud.api.VerifySyncResponse;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.db.SyncRecordDao;
import com.yyba.cloud.util.HashUtils;
import com.yyba.cloud.util.NetworkHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 指纹批量校验：请求只带 fileHash+fileSize+fileName+localUri，
 * 不填 relativePath 绕过服务端路径守卫，使同一内容无论从哪个入口访问都能命中。
 * 响应按 remotePath 前缀区分来源。
 */
public final class FileHashVerifyScheduler {

    public static final int PAGE_SIZE = 50;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "backup-hash-verify");
        t.setDaemon(true);
        return t;
    });

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onVerifyComplete(VerifyResult result);
    }

    public static final class Target {
        public final int position;
        /** 可以是 content://media/... 或纯文件路径 */
        public final String localUri;
        public final String fileName;
        public final long fileSize;
        public String fileHash;

        public Target(int position, String localUri, String fileName, long fileSize) {
            this.position = position;
            this.localUri = localUri;
            this.fileName = fileName;
            this.fileSize = fileSize;
        }
    }

    public static final class VerifyResult {
        public final Map<Integer, FileEntry.BackupStatus> statusByPosition;
        public final Map<Integer, String> sourceByPosition;

        public VerifyResult(Map<Integer, FileEntry.BackupStatus> statusByPosition,
                            Map<Integer, String> sourceByPosition) {
            this.statusByPosition = statusByPosition;
            this.sourceByPosition = sourceByPosition;
        }

        static VerifyResult empty() {
            return new VerifyResult(new HashMap<>(), new HashMap<>());
        }
    }

    /** 立即校验（不走防抖） */
    public void verifyNow(Context context, List<Target> targets, ApiClient api, Callback callback) {
        if (context == null || targets == null || targets.isEmpty() || callback == null) {
            if (callback != null) {
                callback.onVerifyComplete(VerifyResult.empty());
            }
            return;
        }
        Context app = context.getApplicationContext();
        if (!NetworkHelper.hasValidatedNetwork(app)) {
            callback.onVerifyComplete(VerifyResult.empty());
            return;
        }
        EXECUTOR.execute(() -> {
            VerifyResult result;
            try {
                result = executeVerify(app, targets, api);
            } catch (Exception e) {
                result = VerifyResult.empty();
            }
            VerifyResult finalResult = result;
            mainHandler.post(() -> callback.onVerifyComplete(finalResult));
        });
    }

    private static VerifyResult executeVerify(Context app, List<Target> targets, ApiClient api)
            throws Exception {
        Map<String, Target> localUriToTarget = new HashMap<>();
        Map<Integer, FileEntry.BackupStatus> statusByPosition = new HashMap<>();
        Map<Integer, String> sourceByPosition = new HashMap<>();

        // 计算指纹（优先用 ContentResolver 读 content://，fallback FileInputStream 读纯路径）
        List<Target> withHash = new ArrayList<>();
        for (Target target : targets) {
            if (target.fileHash == null || target.fileHash.isEmpty()) {
                target.fileHash = computeHash(app, target.localUri);
            }
            if (target.fileHash != null && !target.fileHash.isEmpty()) {
                withHash.add(target);
                localUriToTarget.putIfAbsent(target.localUri, target);
                statusByPosition.put(target.position, FileEntry.BackupStatus.NOT_BACKED_UP);
            }
        }

        if (withHash.isEmpty()) {
            return new VerifyResult(statusByPosition, sourceByPosition);
        }

        // 分页校验
        SyncRecordDao dao = AppDatabase.getInstance(app).syncRecordDao();
        for (int offset = 0; offset < withHash.size(); offset += PAGE_SIZE) {
            int end = Math.min(offset + PAGE_SIZE, withHash.size());
            List<Target> batch = withHash.subList(offset, end);

            List<VerifySyncItem> items = new ArrayList<>();
            for (Target target : batch) {
                VerifySyncItem item = new VerifySyncItem();
                item.fileHash = target.fileHash;
                item.fileSize = target.fileSize > 0 ? target.fileSize : null;
                item.fileName = target.fileName;
                item.localUri = target.localUri;
                items.add(item);
            }

            try {
                VerifySyncResponse response = api.verifySyncItems(items);
                if (response != null && response.rebuiltRecords != null) {
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

                        Target target = localUriToTarget.get(dto.localUri);
                        if (target != null) {
                            statusByPosition.put(target.position, FileEntry.BackupStatus.BACKED_UP);
                            sourceByPosition.put(target.position,
                                    BackupStatusResolver.resolveSource(dto.remotePath));
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
            } catch (Exception ignored) {
            }
        }

        return new VerifyResult(statusByPosition, sourceByPosition);
    }

    /** 计算 SHA-256：优先 ContentResolver（content://），fallback FileInputStream（纯路径） */
    private static String computeHash(Context app, String localUri) {
        if (localUri == null) return null;
        // content:// → ContentResolver
        if (localUri.startsWith("content://")) {
            try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(localUri))) {
                if (in == null) return null;
                return HashUtils.sha256(in);
            } catch (Exception e) {
                return null;
            }
        }
        // 纯路径 → FileInputStream
        File file = new File(localUri);
        if (!file.isFile() || !file.canRead()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            return HashUtils.sha256(fis);
        } catch (Exception e) {
            return null;
        }
    }
}
