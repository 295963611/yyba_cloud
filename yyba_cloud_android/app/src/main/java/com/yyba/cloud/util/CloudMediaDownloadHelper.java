package com.yyba.cloud.util;

import android.content.Context;
import android.net.Uri;

import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.api.SyncRecordDto;
import com.yyba.cloud.api.VerifySyncItem;
import com.yyba.cloud.api.VerifySyncResponse;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.db.SyncRecordDao;
import com.yyba.cloud.sync.MediaScanner;

import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.List;

/**
 * 云端媒体下载后绑定本地 SyncRecord；手机/云端 Tab 各自刷新状态。
 */
public final class CloudMediaDownloadHelper {

    private CloudMediaDownloadHelper() {
    }

    /** 是否应保存到相册并建立同步关系（照片/视频） */
    public static boolean shouldBindToGallery(String cloudRemotePath) {
        if (cloudRemotePath == null || cloudRemotePath.isEmpty()) {
            return false;
        }
        return cloudRemotePath.startsWith("photos/")
                || cloudRemotePath.startsWith("videos/");
    }

    /**
     * 下载云端文件到临时文件，关联或写入相册并绑定唯一 SyncRecord。
     */
    public static DownloadResult downloadBindAndSave(Context context,
                                                     ApiClient apiClient,
                                                     String cloudRemotePath,
                                                     Long ownerId,
                                                     ApiClient.DownloadProgressListener progress) throws Exception {
        return downloadBindAndSave(context, apiClient, cloudRemotePath, ownerId, null, progress);
    }

    public static DownloadResult downloadBindAndSave(Context context,
                                                     ApiClient apiClient,
                                                     String cloudRemotePath,
                                                     Long ownerId,
                                                     Long remoteFileId,
                                                     ApiClient.DownloadProgressListener progress) throws Exception {
        Context app = context.getApplicationContext();
        SyncRecordDao dao = AppDatabase.getInstance(app).syncRecordDao();

        SyncRecord existing = dao.findByRemotePath(cloudRemotePath);
        if (existing != null && SyncStatusHelper.localMediaExists(app, existing)) {
            ensureRemoteFileId(app, apiClient, existing, remoteFileId);
            DownloadHelper.SavedMedia saved = savedMediaFromRecord(app, existing);
            return new DownloadResult(fileNameFromPath(cloudRemotePath), saved, true, false, existing);
        }
        if (existing != null) {
            dao.deleteByRemotePath(cloudRemotePath);
        }

        String fileName = fileNameFromPath(cloudRemotePath);
        String suffix = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.')) : ".bin";
        File temp = File.createTempFile("yybacloud-dl-", suffix, app.getCacheDir());
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                apiClient.downloadFileStream(cloudRemotePath, ownerId, out, progress);
            }
            String hash;
            try (FileInputStream in = new FileInputStream(temp)) {
                hash = HashUtils.sha256(in);
            }
            long fileSize = temp.length();
            String mediaType = cloudRemotePath.startsWith("videos/") ? "VIDEO" : "IMAGE";

            DownloadHelper.SavedMedia linked = tryLinkExistingLocal(
                    app, dao, cloudRemotePath, hash, fileSize, mediaType);
            if (linked != null) {
                SyncRecord record = upsertCloudBinding(app, apiClient, linked, cloudRemotePath, hash, remoteFileId);
                notifyDownloaded(cloudRemotePath, linked);
                return new DownloadResult(fileName, linked, false, true, record);
            }

            DownloadHelper.SavedMedia saved = DownloadHelper.saveMediaFromFile(
                    app, cloudRemotePath, fileName, temp);
            SyncRecord record = upsertCloudBinding(app, apiClient, saved, cloudRemotePath, hash, remoteFileId);
            notifyDownloaded(cloudRemotePath, saved);
            return new DownloadResult(fileName, saved, false, false, record);
        } finally {
            if (!temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    private static DownloadHelper.SavedMedia tryLinkExistingLocal(Context app, SyncRecordDao dao,
                                                                  String cloudRemotePath, String hash,
                                                                  long fileSize, String mediaType) {
        List<SyncRecord> sameHash = dao.findByFileHash(hash);
        for (SyncRecord record : sameHash) {
            if (!SyncStatusHelper.localMediaExists(app, record)) {
                continue;
            }
            DownloadHelper.SavedMedia saved = savedMediaFromRecord(app, record);
            if (saved != null) {
                return saved;
            }
        }
        MediaScanner.MediaItem item = new MediaScanner().findByContentHash(
                app, mediaType, hash, fileSize);
        if (item == null) {
            return null;
        }
        return savedMediaFromItem(item);
    }

    static SyncRecord upsertCloudBinding(Context app, ApiClient apiClient, DownloadHelper.SavedMedia saved,
                                         String cloudRemotePath, String fileHash, Long remoteFileId) {
        SyncRecordDao dao = AppDatabase.getInstance(app).syncRecordDao();
        dao.deleteByRemotePath(cloudRemotePath);
        bindToCloud(app, saved, cloudRemotePath, fileHash, remoteFileId);
        SyncRecord record = dao.findByRemotePath(cloudRemotePath);
        if (record == null && saved.uri != null) {
            record = dao.findByLocalUri(saved.uri.toString());
        }
        ensureRemoteFileId(app, apiClient, record, remoteFileId);
        return dao.findByRemotePath(cloudRemotePath);
    }

    static void upsertCloudBinding(Context app, DownloadHelper.SavedMedia saved,
                                   String cloudRemotePath, String fileHash) {
        upsertCloudBinding(app, null, saved, cloudRemotePath, fileHash, null);
    }

    private static void ensureRemoteFileId(Context app, ApiClient apiClient, SyncRecord record,
                                           Long remoteFileId) {
        if (record == null) {
            return;
        }
        if (remoteFileId != null && remoteFileId > 0) {
            if (record.remoteFileId == null || !remoteFileId.equals(record.remoteFileId)) {
                record.remoteFileId = remoteFileId;
                AppDatabase.getInstance(app).syncRecordDao().insert(record);
            }
            return;
        }
        resolveRemoteFileIdViaVerify(app, apiClient, record);
    }

    private static void resolveRemoteFileIdViaVerify(Context app, ApiClient apiClient, SyncRecord record) {
        if (record == null || apiClient == null) {
            return;
        }
        if (record.remoteFileId != null && record.remoteFileId > 0) {
            return;
        }
        if (record.localUri == null || record.remotePath == null || record.remotePath.isEmpty()) {
            return;
        }
        try {
            VerifySyncItem item = new VerifySyncItem();
            item.localUri = record.localUri;
            item.relativePath = record.remotePath;
            if (record.fileSize > 0) {
                item.fileSize = record.fileSize;
            }
            item.fileName = record.fileName;
            item.mediaType = record.mediaType;
            if (record.mediaId != null && record.mediaId > 0) {
                item.mediaId = record.mediaId;
            }
            if (record.modifiedAt > 0) {
                item.capturedAt = record.modifiedAt;
            }
            if (record.fileHash != null && !record.fileHash.isEmpty()) {
                item.fileHash = record.fileHash;
            }
            VerifySyncResponse response = apiClient.verifySyncItems(Collections.singletonList(item));
            if (response == null || response.rebuiltRecords == null || response.rebuiltRecords.isEmpty()) {
                return;
            }
            SyncRecordDto dto = response.rebuiltRecords.get(0);
            if (dto == null || dto.remoteFileId == null || dto.remoteFileId <= 0) {
                return;
            }
            record.remoteFileId = dto.remoteFileId;
            if (dto.remotePath != null && !dto.remotePath.isEmpty()) {
                record.remotePath = dto.remotePath;
            }
            if (dto.fileHash != null && !dto.fileHash.isEmpty()) {
                record.fileHash = dto.fileHash;
            }
            record.syncedAt = System.currentTimeMillis();
            AppDatabase.getInstance(app).syncRecordDao().insert(record);
        } catch (Exception ignored) {
        }
    }

    private static void notifyDownloaded(String cloudRemotePath, DownloadHelper.SavedMedia saved) {
        PendingMediaRefresh.markDownloaded(cloudRemotePath, saved);
        CloudDownloadNotifier.notifyDownloaded(cloudRemotePath, saved);
    }

    public static final class DownloadResult {
        public final String fileName;
        public final DownloadHelper.SavedMedia saved;
        /** 该云端路径此前已绑定且本地文件仍在 */
        public final boolean alreadyOnDevice;
        /** 未新建文件，关联了已有本地媒体 */
        public final boolean linkedExisting;
        public final SyncRecord syncRecord;

        DownloadResult(String fileName, DownloadHelper.SavedMedia saved,
                       boolean alreadyOnDevice, boolean linkedExisting, SyncRecord syncRecord) {
            this.fileName = fileName;
            this.saved = saved;
            this.alreadyOnDevice = alreadyOnDevice;
            this.linkedExisting = linkedExisting;
            this.syncRecord = syncRecord;
        }
    }

    static DownloadHelper.SavedMedia savedMediaFromRecord(Context app, SyncRecord record) {
        if (record == null || record.localUri == null) {
            return null;
        }
        MediaScanner.MediaItem item = new MediaScanner().queryByUri(
                app, Uri.parse(record.localUri), record.mediaType);
        if (item != null) {
            return savedMediaFromItem(item);
        }
        return new DownloadHelper.SavedMedia(
                Uri.parse(record.localUri),
                record.mediaId != null ? record.mediaId : 0L,
                record.fileName,
                record.fileSize,
                record.modifiedAt,
                record.mediaType);
    }

    static DownloadHelper.SavedMedia savedMediaFromItem(MediaScanner.MediaItem item) {
        if (item == null || item.uri == null) {
            return null;
        }
        return new DownloadHelper.SavedMedia(
                item.uri,
                item.mediaId,
                item.fileName,
                item.fileSize,
                item.modifiedAt,
                item.mediaType);
    }

    public static void bindToCloud(Context app, DownloadHelper.SavedMedia saved,
                                   String cloudRemotePath, String fileHash) {
        bindToCloud(app, saved, cloudRemotePath, fileHash, null);
    }

    public static void bindToCloud(Context app, DownloadHelper.SavedMedia saved,
                                   String cloudRemotePath, String fileHash, Long remoteFileId) {
        SyncRecord record = new SyncRecord();
        record.localUri = saved.uri.toString();
        record.mediaId = saved.mediaId;
        record.fileName = saved.fileName;
        record.fileSize = saved.fileSize;
        record.mediaType = saved.mediaType;
        record.modifiedAt = saved.modifiedAtMs;
        record.remotePath = cloudRemotePath;
        record.fileHash = fileHash != null ? fileHash : "";
        record.syncedAt = System.currentTimeMillis();
        if (remoteFileId != null && remoteFileId > 0) {
            record.remoteFileId = remoteFileId;
        }
        AppDatabase.getInstance(app).syncRecordDao().insert(record);
    }

    static String fileNameFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return "download.bin";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }
}
