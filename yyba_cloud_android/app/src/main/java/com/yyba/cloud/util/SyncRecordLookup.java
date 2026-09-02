package com.yyba.cloud.util;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import com.yyba.cloud.api.AlbumPhotoItem;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.sync.MediaScanner;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按页/按批查询 sync_records，替代全表 LocalIndexCache */
public final class SyncRecordLookup {

    public static final int BATCH_SIZE = 50;

    private SyncRecordLookup() {
    }

    public static Map<String, SyncRecord> indexByLocalUri(List<SyncRecord> records) {
        Map<String, SyncRecord> index = new HashMap<>();
        if (records == null) {
            return index;
        }
        for (SyncRecord record : records) {
            if (record != null && record.localUri != null) {
                index.put(record.localUri, record);
            }
        }
        return index;
    }

    public static Map<String, SyncRecord> loadByLocalUris(Context context, Collection<String> uris) {
        if (context == null || uris == null || uris.isEmpty()) {
            return Map.of();
        }
        var dao = AppDatabase.getInstance(context.getApplicationContext()).syncRecordDao();
        ArrayList<String> batch = new ArrayList<>(BATCH_SIZE);
        Map<String, SyncRecord> index = new HashMap<>();
        for (String uri : uris) {
            if (uri == null || uri.isEmpty()) {
                continue;
            }
            batch.add(uri);
            if (batch.size() >= BATCH_SIZE) {
                mergeLocalUriBatch(index, dao.findByLocalUris(new ArrayList<>(batch)));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            mergeLocalUriBatch(index, dao.findByLocalUris(batch));
        }
        return index;
    }

    public static Map<String, SyncRecord> loadByRemotePaths(Context context, Collection<String> paths) {
        if (context == null || paths == null || paths.isEmpty()) {
            return Map.of();
        }
        var dao = AppDatabase.getInstance(context.getApplicationContext()).syncRecordDao();
        ArrayList<String> batch = new ArrayList<>(BATCH_SIZE);
        Map<String, SyncRecord> index = new HashMap<>();
        for (String path : paths) {
            if (path == null || path.isEmpty()) {
                continue;
            }
            batch.add(path);
            if (batch.size() >= BATCH_SIZE) {
                mergeRemotePathBatch(index, dao.findByRemotePaths(new ArrayList<>(batch)));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            mergeRemotePathBatch(index, dao.findByRemotePaths(batch));
        }
        return index;
    }

    public static Map<Long, SyncRecord> loadByRemoteFileIds(Context context, Collection<Long> fileIds) {
        if (context == null || fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        var dao = AppDatabase.getInstance(context.getApplicationContext()).syncRecordDao();
        ArrayList<Long> batch = new ArrayList<>(BATCH_SIZE);
        Map<Long, SyncRecord> index = new HashMap<>();
        for (Long fileId : fileIds) {
            if (fileId == null || fileId <= 0) {
                continue;
            }
            batch.add(fileId);
            if (batch.size() >= BATCH_SIZE) {
                mergeFileIdBatch(index, dao.findByRemoteFileIds(new ArrayList<>(batch)));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            mergeFileIdBatch(index, dao.findByRemoteFileIds(batch));
        }
        return index;
    }

    /** 手机 Tab 单页：localUri 批量查库，并补齐同页 remotePath 关联记录（去重用） */
    public static Map<String, SyncRecord> loadForPhonePage(Context context,
                                                           Collection<String> localUris) {
        Map<String, SyncRecord> byUri = new HashMap<>(loadByLocalUris(context, localUris));
        Set<String> remotePaths = new HashSet<>();
        for (SyncRecord record : byUri.values()) {
            if (record.remotePath != null && !record.remotePath.isEmpty()) {
                remotePaths.add(record.remotePath);
            }
        }
        if (remotePaths.isEmpty()) {
            return byUri;
        }
        Map<String, SyncRecord> byRemote = loadByRemotePaths(context, remotePaths);
        for (SyncRecord record : byRemote.values()) {
            if (record.localUri != null) {
                byUri.putIfAbsent(record.localUri, record);
            }
        }
        return byUri;
    }

    public static Map<String, String> buildCanonicalUriByRemotePath(Collection<SyncRecord> records) {
        Map<String, String> map = new HashMap<>();
        if (records == null) {
            return map;
        }
        for (SyncRecord record : records) {
            if (record.remotePath != null && !record.remotePath.isEmpty()
                    && record.localUri != null) {
                map.putIfAbsent(record.remotePath, record.localUri);
            }
        }
        return map;
    }

    public static boolean isDuplicateOfBoundCloudFile(Context context,
                                                      MediaScanner.MediaItem item,
                                                      SyncRecord ownRecord) {
        if (context == null || item == null || ownRecord != null) {
            return false;
        }
        if (item.fileName == null || item.fileSize <= 0) {
            return false;
        }
        SyncRecord match = AppDatabase.getInstance(context.getApplicationContext()).syncRecordDao()
                .findSyncedByNameAndSize(item.fileName, item.fileSize);
        return match != null;
    }

    /** 首页：sync_records 已同步且 localUri 仍在 MediaStore 中的条数 */
    public static int countValidSyncedPresentInMediaStore(Context context, String mediaType) {
        if (context == null || mediaType == null) {
            return 0;
        }
        Context app = context.getApplicationContext();
        List<SyncRecord> synced = AppDatabase.getInstance(app).syncRecordDao()
                .findSyncedWithLocalUriByMediaType(mediaType);
        if (synced.isEmpty()) {
            return 0;
        }
        Uri collection = collectionForMediaType(mediaType);
        if (collection == null) {
            return 0;
        }
        ArrayList<Long> idBatch = new ArrayList<>(BATCH_SIZE);
        int present = 0;
        for (SyncRecord record : synced) {
            Long mediaId = mediaIdFromRecord(record);
            if (mediaId == null) {
                continue;
            }
            idBatch.add(mediaId);
            if (idBatch.size() >= BATCH_SIZE) {
                present += countExistingMediaIds(app, collection, idBatch);
                idBatch.clear();
            }
        }
        if (!idBatch.isEmpty()) {
            present += countExistingMediaIds(app, collection, idBatch);
        }
        return present;
    }

    private static Uri collectionForMediaType(String mediaType) {
        if ("IMAGE".equals(mediaType)) {
            return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
        if ("VIDEO".equals(mediaType)) {
            return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }
        return null;
    }

    private static Long mediaIdFromRecord(SyncRecord record) {
        if (record == null) {
            return null;
        }
        if (record.mediaId != null && record.mediaId > 0) {
            return record.mediaId;
        }
        if (record.localUri == null || record.localUri.isEmpty()) {
            return null;
        }
        try {
            long id = ContentUris.parseId(Uri.parse(record.localUri));
            return id > 0 ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int countExistingMediaIds(Context context, Uri collection, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        StringBuilder selection = new StringBuilder(MediaStore.MediaColumns._ID + " IN (");
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                selection.append(',');
            }
            selection.append('?');
            args[i] = String.valueOf(ids.get(i));
        }
        selection.append(')');
        Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.toString());
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args);
        try (Cursor cursor = context.getContentResolver().query(
                collection, new String[]{MediaStore.MediaColumns._ID}, queryArgs, null)) {
            return cursor != null ? cursor.getCount() : 0;
        }
    }

    /** 云端 Tab 单页批量查库 */
    public static CloudPageLookup loadForCloudPage(Context context, List<AlbumPhotoItem> items) {
        if (context == null || items == null || items.isEmpty()) {
            return CloudPageLookup.empty();
        }
        Set<Long> fileIds = new HashSet<>();
        Set<String> remotePaths = new HashSet<>();
        for (AlbumPhotoItem item : items) {
            if (item == null) {
                continue;
            }
            if (item.fileId != null && item.fileId > 0) {
                fileIds.add(item.fileId);
            }
            if (item.relativePath != null && !item.relativePath.isEmpty()) {
                remotePaths.add(item.relativePath);
            }
        }
        Map<Long, SyncRecord> byFileId = loadByRemoteFileIds(context, fileIds);
        Map<String, SyncRecord> byRemotePath = loadByRemotePaths(context, remotePaths);
        Map<String, SyncRecord> byNameSize = loadByNameSize(context, items);
        return new CloudPageLookup(context.getApplicationContext(), byFileId, byRemotePath, byNameSize);
    }

    private static Map<String, SyncRecord> loadByNameSize(Context context, List<AlbumPhotoItem> items) {
        Map<String, SyncRecord> index = new HashMap<>();
        if (context == null || items == null || items.isEmpty()) {
            return index;
        }
        var dao = AppDatabase.getInstance(context.getApplicationContext()).syncRecordDao();
        HashSet<String> seen = new HashSet<>();
        for (AlbumPhotoItem item : items) {
            if (item == null || item.relativePath == null || item.fileSize == null || item.fileSize <= 0) {
                continue;
            }
            String fileName = fileNameFromPath(item.relativePath);
            if (fileName.isEmpty()) {
                continue;
            }
            String key = nameSizeKey(fileName, item.fileSize);
            if (!seen.add(key)) {
                continue;
            }
            SyncRecord record = dao.findSyncedByNameAndSize(fileName, item.fileSize);
            if (record != null) {
                index.put(key, record);
            }
        }
        return index;
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

    private static void mergeLocalUriBatch(Map<String, SyncRecord> index, List<SyncRecord> batch) {
        if (batch == null) {
            return;
        }
        for (SyncRecord record : batch) {
            if (record == null || record.localUri == null) {
                continue;
            }
            SyncRecord existing = index.get(record.localUri);
            if (existing == null || preferSyncedRecord(record, existing)) {
                index.put(record.localUri, record);
            }
        }
    }

    private static boolean preferSyncedRecord(SyncRecord candidate, SyncRecord existing) {
        boolean candidateSynced = candidate.remoteFileId != null && candidate.remoteFileId > 0;
        boolean existingSynced = existing.remoteFileId != null && existing.remoteFileId > 0;
        return candidateSynced && !existingSynced;
    }

    private static void mergeRemotePathBatch(Map<String, SyncRecord> index, List<SyncRecord> batch) {
        if (batch == null) {
            return;
        }
        for (SyncRecord record : batch) {
            if (record != null && record.remotePath != null) {
                index.putIfAbsent(record.remotePath, record);
            }
        }
    }

    private static void mergeFileIdBatch(Map<Long, SyncRecord> index, List<SyncRecord> batch) {
        if (batch == null) {
            return;
        }
        for (SyncRecord record : batch) {
            if (record != null && record.remoteFileId != null && record.remoteFileId > 0) {
                index.putIfAbsent(record.remoteFileId, record);
            }
        }
    }

    public static final class DownloadPatch {
        public final boolean downloaded;
        public final String localThumbUri;

        public DownloadPatch(boolean downloaded, String localThumbUri) {
            this.downloaded = downloaded;
            this.localThumbUri = localThumbUri;
        }
    }

    public static final class CloudPageLookup {
        private final Context appContext;
        private final Map<Long, SyncRecord> byFileId;
        private final Map<String, SyncRecord> byRemotePath;
        private final Map<String, SyncRecord> byNameSize;

        private CloudPageLookup(Context appContext,
                                Map<Long, SyncRecord> byFileId,
                                Map<String, SyncRecord> byRemotePath,
                                Map<String, SyncRecord> byNameSize) {
            this.appContext = appContext;
            this.byFileId = byFileId != null ? byFileId : Map.of();
            this.byRemotePath = byRemotePath != null ? byRemotePath : Map.of();
            this.byNameSize = byNameSize != null ? byNameSize : Map.of();
        }

        static CloudPageLookup empty() {
            return new CloudPageLookup(null, Map.of(), Map.of(), Map.of());
        }

        public DownloadPatch resolveDownload(AlbumPhotoItem cloudItem) {
            String localUri = localUriIfPresent(cloudItem);
            return new DownloadPatch(localUri != null, localUri);
        }

        public boolean isDownloaded(AlbumPhotoItem cloudItem) {
            return localUriIfPresent(cloudItem) != null;
        }

        @Nullable
        public String localUriIfPresent(AlbumPhotoItem cloudItem) {
            if (appContext == null || cloudItem == null) {
                return null;
            }
            SyncRecord record = findMatchingRecord(cloudItem);
            if (record != null && record.localUri != null
                    && SyncStatusHelper.localMediaExists(appContext, record)) {
                return record.localUri;
            }
            return null;
        }

        @Nullable
        private SyncRecord findMatchingRecord(AlbumPhotoItem cloudItem) {
            if (cloudItem.fileId != null && cloudItem.fileId > 0) {
                SyncRecord byId = byFileId.get(cloudItem.fileId);
                if (byId != null) {
                    return byId;
                }
            }
            if (cloudItem.relativePath != null) {
                SyncRecord byPath = byRemotePath.get(cloudItem.relativePath);
                if (byPath != null) {
                    return byPath;
                }
            }
            if (cloudItem.relativePath != null && cloudItem.fileSize != null && cloudItem.fileSize > 0) {
                String fileName = fileNameFromPath(cloudItem.relativePath);
                return byNameSize.get(nameSizeKey(fileName, cloudItem.fileSize));
            }
            return null;
        }
    }
}
