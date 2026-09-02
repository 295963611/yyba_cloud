package com.yyba.cloud.util;

import android.content.Context;

import com.yyba.cloud.api.AlbumPhotoItem;
import com.yyba.cloud.api.SyncIndexItem;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.sync.MediaScanner;
import com.yyba.cloud.util.HashUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SyncStatusHelper {

    private SyncStatusHelper() {}

    /**
     * 列表展示用：本地 sync_records 中 remoteFileId 非空即视为已同步（后续 verify-sync 可校正）。
     */
    public static boolean isSyncedByRecord(SyncRecord record) {
        return record != null && record.remoteFileId != null && record.remoteFileId > 0;
    }

    /**
     * 列表展示用：仅当本地映射的 remotePath 存在于当前云端索引时判定为已同步。
     * 索引未加载时不猜测，避免误标。
     * @deprecated 手机 Tab 改用 {@link #isSyncedByRecord(SyncRecord)} + verify-sync
     */
    @Deprecated
    public static boolean isSyncedFast(Context context, Map<String, SyncRecord> localIndex,
                                       Set<String> serverPaths, MediaScanner.MediaItem item) {
        if (item == null || item.uri == null) {
            return false;
        }
        if (serverPaths == null || serverPaths.isEmpty()) {
            return false;
        }
        SyncRecord record = localIndex.get(item.uri.toString());
        return isRecordSyncedOnServer(record, serverPaths);
    }

    /**
     * 云端 Tab：该项是否已在手机上有可用副本。
     * 含 sync_records 映射且文件仍在，或文件名+大小与有效本地记录一致。
     */
    /** 云端 Tab：该项是否已在手机上有可用副本。 */
    public static boolean isDownloadedFast(Context context, Map<String, SyncRecord> localIndex,
                                           AlbumPhotoItem cloudItem) {
        if (context == null || cloudItem == null) {
            return false;
        }
        if (cloudItem.fileId != null && cloudItem.fileId > 0 && localIndex != null) {
            for (SyncRecord record : localIndex.values()) {
                if (cloudItem.fileId.equals(record.remoteFileId) && localMediaExists(context, record)) {
                    return true;
                }
            }
        }
        if (cloudItem.relativePath == null) {
            return false;
        }
        com.yyba.cloud.api.SyncIndexItem indexItem = new com.yyba.cloud.api.SyncIndexItem();
        indexItem.relativePath = cloudItem.relativePath;
        indexItem.fileSize = cloudItem.fileSize;
        indexItem.sha256 = cloudItem.sha256;
        return isDownloadedFast(context, localIndex, indexItem);
    }

    public static boolean isDownloadedFast(Context context, Map<String, SyncRecord> localIndex,
                                           SyncIndexItem cloudItem) {
        if (context == null || cloudItem == null || cloudItem.relativePath == null) {
            return false;
        }
        String remotePath = cloudItem.relativePath;
        if (localIndex != null) {
            for (SyncRecord record : localIndex.values()) {
                if (!remotePath.equals(record.remotePath)) {
                    continue;
                }
                if (localMediaExists(context, record)) {
                    return true;
                }
            }
            Long size = cloudItem.fileSize;
            if (size != null && size > 0) {
                String fileName = fileNameFromPath(remotePath);
                for (SyncRecord record : localIndex.values()) {
                    if (record.fileName == null || !record.fileName.equals(fileName)) {
                        continue;
                    }
                    if (record.fileSize != size) {
                        continue;
                    }
                    if (localMediaExists(context, record)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 本地映射是否对应当前云端索引中的文件 */
    public static boolean isRecordSyncedOnServer(SyncRecord record, Set<String> serverPaths) {
        if (record == null || serverPaths == null || serverPaths.isEmpty()) {
            return false;
        }
        if (isHiddenCloudDeleted(record, serverPaths)) {
            return false;
        }
        return record.remotePath != null && !record.remotePath.isEmpty()
                && serverPaths.contains(record.remotePath);
    }

    /** 文件名+大小 → 云端 relativePath（仅供后台 reconcile，不用于列表快速判定） */
    public static Map<String, String> buildCloudPathByNameSize(List<SyncIndexItem> items) {
        Map<String, String> map = new HashMap<>();
        if (items == null) {
            return map;
        }
        for (SyncIndexItem item : items) {
            if (item == null || item.relativePath == null || item.relativePath.isEmpty()) {
                continue;
            }
            if (item.fileSize == null || item.fileSize <= 0) {
                continue;
            }
            String fileName = fileNameFromPath(item.relativePath);
            if (fileName.isEmpty()) {
                continue;
            }
            map.put(nameSizeKey(fileName, item.fileSize), item.relativePath);
        }
        return map;
    }

    public static String lookupCloudPath(MediaScanner.MediaItem item, Set<String> serverPaths,
                                         Map<String, String> cloudPathByNameSize) {
        if (item == null || item.fileName == null || item.fileSize <= 0
                || cloudPathByNameSize == null || cloudPathByNameSize.isEmpty()
                || serverPaths == null || serverPaths.isEmpty()) {
            return null;
        }
        String cloudPath = cloudPathByNameSize.get(nameSizeKey(item.fileName, item.fileSize));
        if (cloudPath == null || cloudPath.isEmpty()) {
            return null;
        }
        return serverPaths.contains(cloudPath) ? cloudPath : null;
    }

    private static String nameSizeKey(String fileName, long fileSize) {
        return fileName + "|" + fileSize;
    }

    private static String fileNameFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** 本地文件是否仍可访问（用于判断已有绑定是否有效） */
    public static boolean localMediaExists(Context context, SyncRecord record) {
        if (context == null || record == null || record.localUri == null) {
            return false;
        }
        try (InputStream in = context.getContentResolver().openInputStream(
                android.net.Uri.parse(record.localUri))) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 构建云端路径 -> 权威本地 URI，用于列表去重 */
    public static Map<String, String> buildCanonicalUriByRemotePath(Map<String, SyncRecord> localIndex) {
        Map<String, String> map = new HashMap<>();
        if (localIndex == null) {
            return map;
        }
        for (SyncRecord record : localIndex.values()) {
            if (record.remotePath != null && !record.remotePath.isEmpty()
                    && record.localUri != null) {
                map.put(record.remotePath, record.localUri);
            }
        }
        return map;
    }

    /**
     * 扫描列表时隐藏与已绑定云端文件同内容但未关联的副本（如相机原图 + 悠悠爸云盘副本）。
     */
    public static boolean isDuplicateOfBoundCloudFile(MediaScanner.MediaItem item,
                                                      SyncRecord ownRecord,
                                                      Map<String, SyncRecord> localIndex) {
        if (item == null || ownRecord != null || localIndex == null || localIndex.isEmpty()) {
            return false;
        }
        for (SyncRecord record : localIndex.values()) {
            if (!isSyncedByRecord(record)) {
                continue;
            }
            if (record.fileName != null && record.fileName.equals(item.fileName)
                    && record.fileSize > 0 && record.fileSize == item.fileSize) {
                return true;
            }
        }
        return false;
    }

    /** @deprecated 使用 {@link #isDuplicateOfBoundCloudFile(MediaScanner.MediaItem, SyncRecord, Map)} */
    @Deprecated
    public static boolean isDuplicateOfBoundCloudFile(MediaScanner.MediaItem item,
                                                      SyncRecord ownRecord,
                                                      Map<String, SyncRecord> localIndex,
                                                      Set<String> serverPaths) {
        if (serverPaths == null || serverPaths.isEmpty()) {
            return isDuplicateOfBoundCloudFile(item, ownRecord, localIndex);
        }
        if (item == null || ownRecord != null || localIndex == null || localIndex.isEmpty()) {
            return false;
        }
        for (SyncRecord record : localIndex.values()) {
            if (record.remotePath == null || record.remotePath.isEmpty()) {
                continue;
            }
            if (!serverPaths.contains(record.remotePath)) {
                continue;
            }
            if (record.fileName != null && record.fileName.equals(item.fileName)
                    && record.fileSize > 0 && record.fileSize == item.fileSize) {
                return true;
            }
        }
        return false;
    }

    /** 已绑定云端但 verify 尚未清空：不再依赖 cloud_index，由 verify-sync 负责降级 */
    @Deprecated
    public static boolean isHiddenCloudDeleted(SyncRecord record, Set<String> serverPaths) {
        if (record == null || record.remotePath == null || record.remotePath.isEmpty()) {
            return false;
        }
        if (!isCloudRelativePath(record.remotePath)) {
            return false;
        }
        if (serverPaths == null || serverPaths.isEmpty()) {
            return false;
        }
        return !serverPaths.contains(record.remotePath);
    }

    public static boolean isCloudRelativePath(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("photos/")
                || path.startsWith("videos/")
                || path.startsWith("audio/")
                || path.startsWith("uploads/");
    }

    /** 将服务端已存在的文件写入本地记录，便于离线识别。 */
    public static void cacheServerMatch(Context context, MediaScanner.MediaItem item) {
        cacheServerMatch(context, item, null);
    }

    public static void cacheServerMatch(Context context, MediaScanner.MediaItem item,
                                        String serverRelativePath) {
        if (item == null) {
            return;
        }
        if (serverRelativePath != null && !serverRelativePath.isEmpty()) {
            item.serverMatchedPath = serverRelativePath;
        }
        cacheServerMatchesBatch(context, java.util.Collections.singletonList(item));
    }

    /** 批量写入；remotePath 必须是已确认的云端路径。 */
    public static void cacheServerMatchesBatch(Context context, List<MediaScanner.MediaItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<String> serverPaths = ServerSyncCache.peekSyncedPaths();
        long now = System.currentTimeMillis();
        List<SyncRecord> records = new ArrayList<>(items.size());
        for (MediaScanner.MediaItem item : items) {
            if (item == null || item.uri == null) {
                continue;
            }
            String remotePath = item.serverMatchedPath != null && !item.serverMatchedPath.isEmpty()
                    ? item.serverMatchedPath
                    : item.relativePath;
            if (remotePath == null || remotePath.isEmpty()) {
                continue;
            }
            if (!serverPaths.isEmpty() && !serverPaths.contains(remotePath)) {
                continue;
            }
            SyncRecord record = new SyncRecord();
            record.localUri = item.uri.toString();
            record.mediaId = item.mediaId;
            record.fileName = item.fileName;
            record.fileSize = item.fileSize;
            record.mediaType = item.mediaType;
            record.modifiedAt = item.modifiedAt;
            record.remotePath = remotePath;
            record.syncedAt = now;
            records.add(record);
        }
        if (records.isEmpty()) {
            return;
        }
        AppDatabase.getInstance(context.getApplicationContext()).syncRecordDao().insertAll(records);
    }

    /** 含 Room 查询；上传完成后判断 sync_records 是否已有关联。 */
    public static boolean isSynced(Context context, MediaScanner.MediaItem item) {
        if (context == null || item == null || item.uri == null) {
            return false;
        }
        SyncRecord record = AppDatabase.getInstance(context).syncRecordDao()
                .findByLocalUri(item.uri.toString());
        return isSyncedByRecord(record);
    }

    public static int countUnsynced(Context context, List<MediaScanner.MediaItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        ArrayList<String> uris = new ArrayList<>(items.size());
        for (MediaScanner.MediaItem item : items) {
            if (item != null && item.uri != null) {
                uris.add(item.uri.toString());
            }
        }
        Map<String, SyncRecord> localIndex = SyncRecordLookup.loadByLocalUris(context, uris);
        int count = 0;
        for (MediaScanner.MediaItem item : items) {
            SyncRecord record = item.uri != null ? localIndex.get(item.uri.toString()) : null;
            if (!isSyncedByRecord(record)) {
                count++;
            }
        }
        return count;
    }

    /** 首页未同步：照片 / 视频 / 音频（与立即同步 collectUnsynced 同口径） */
    public static int[] countHomeUnsynced(Context context) {
        Context app = context.getApplicationContext();
        Map<String, SyncRecord> index = SyncRecordLookup.indexByLocalUri(
                AppDatabase.getInstance(app).syncRecordDao().findAll());
        MediaScanner scanner = new MediaScanner();
        return new int[]{
                scanner.countUnsynced(app, "IMAGE", index),
                scanner.countUnsynced(app, "VIDEO", index),
                scanner.countUnsynced(app, "AUDIO", index)
        };
    }
}
