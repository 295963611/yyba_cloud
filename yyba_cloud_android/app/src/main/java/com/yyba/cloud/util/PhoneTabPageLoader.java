package com.yyba.cloud.util;

import android.content.Context;

import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.sync.MediaScanner;
import com.yyba.cloud.ui.MediaListAdapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 手机 Tab 首屏 MediaStore 分页构建，供进入相册前预加载 */
public final class PhoneTabPageLoader {

    public static final class FirstPageSnapshot {
        public final List<MediaListAdapter.MediaRow> rows;
        public final List<MediaScanner.MediaItem> thumbItems;
        public final boolean hasMore;
        public final int nextOffset;
        /** 首屏 URI 对应的 sync_records，非全表 */
        public final Map<String, SyncRecord> localIndex;
        public final Set<String> localMediaPaths;
        public final long oldestLocalTakenAt;
        public final boolean hasLocalMedia;
        public final boolean localExhausted;

        FirstPageSnapshot(List<MediaListAdapter.MediaRow> rows,
                          List<MediaScanner.MediaItem> thumbItems,
                          boolean hasMore,
                          int nextOffset,
                          Map<String, SyncRecord> localIndex,
                          Set<String> localMediaPaths,
                          long oldestLocalTakenAt,
                          boolean hasLocalMedia,
                          boolean localExhausted) {
            this.rows = rows;
            this.thumbItems = thumbItems;
            this.hasMore = hasMore;
            this.nextOffset = nextOffset;
            this.localIndex = localIndex;
            this.localMediaPaths = localMediaPaths;
            this.oldestLocalTakenAt = oldestLocalTakenAt;
            this.hasLocalMedia = hasLocalMedia;
            this.localExhausted = localExhausted;
        }
    }

    private PhoneTabPageLoader() {
    }

    public static FirstPageSnapshot loadFirstPage(Context context, String category, int pageSize) {
        Context app = context.getApplicationContext();
        Map<String, Integer> shareByPath = ServerSyncCache.peekShareByPath();
        Set<String> localMediaPaths = new HashSet<>();

        ArrayList<MediaListAdapter.MediaRow> rows = new ArrayList<>();
        ArrayList<MediaScanner.MediaItem> thumbItems = new ArrayList<>();
        HashSet<String> seenUris = new HashSet<>();
        HashSet<String> seenRemotePaths = new HashSet<>();
        long oldestLocalTakenAt = Long.MAX_VALUE;
        boolean hasLocalMedia = false;

        MediaScanner.MediaPage page = new MediaScanner().scanPage(app, category, 0, pageSize);
        ArrayList<String> pageUris = new ArrayList<>();
        for (MediaScanner.MediaItem item : page.items) {
            if (item.uri != null) {
                pageUris.add(item.uri.toString());
            }
        }
        Map<String, SyncRecord> localIndex = SyncRecordLookup.loadForPhonePage(app, pageUris);
        Map<String, String> canonicalUriByRemote = SyncRecordLookup.buildCanonicalUriByRemotePath(
                localIndex.values());

        int itemIndex = 0;
        for (MediaScanner.MediaItem item : page.items) {
            hasLocalMedia = true;
            String uriKey = item.uri != null ? item.uri.toString() : null;
            if (uriKey == null || seenUris.contains(uriKey)) {
                itemIndex++;
                continue;
            }
            if (item.relativePath != null) {
                localMediaPaths.add(item.relativePath);
            }
            if (item.takenAt > 0L && item.takenAt < oldestLocalTakenAt) {
                oldestLocalTakenAt = item.takenAt;
            }
            SyncRecord record = localIndex.get(uriKey);
            if (record != null && record.remotePath != null) {
                String canonical = canonicalUriByRemote.get(record.remotePath);
                if ((canonical != null && !canonical.equals(uriKey))
                        || seenRemotePaths.contains(record.remotePath)
                        || SyncRecordLookup.isDuplicateOfBoundCloudFile(app, item, record)) {
                    itemIndex++;
                    continue;
                }
            }
            boolean synced = SyncStatusHelper.isSyncedByRecord(record);
            String remotePath = record != null ? record.remotePath : null;
            if (remotePath != null && !remotePath.isEmpty()) {
                if (seenRemotePaths.contains(remotePath)) {
                    itemIndex++;
                    continue;
                }
                seenRemotePaths.add(remotePath);
            }
            String sharePath = remotePath != null && !remotePath.isEmpty() ? remotePath : item.relativePath;
            seenUris.add(uriKey);
            int mediaStoreOffset = itemIndex;
            rows.add(MediaListAdapter.MediaRow.local(item, synced, isSharedPath(sharePath, shareByPath),
                    remotePath, mediaStoreOffset));
            if ("IMAGE".equals(item.mediaType) || "VIDEO".equals(item.mediaType)) {
                thumbItems.add(item);
            }
            itemIndex++;
        }

        if ("VIDEO".equals(category) || "IMAGE".equals(category)) {
            sortRowsByCapturedTime(rows);
        }

        int nextOffset = page.items.size();
        boolean localExhausted = !page.hasMore;
        return new FirstPageSnapshot(rows, thumbItems, page.hasMore, nextOffset,
                localIndex, localMediaPaths,
                oldestLocalTakenAt, hasLocalMedia, localExhausted);
    }

    private static boolean isSharedPath(String path, Map<String, Integer> shareByPath) {
        if (path == null || shareByPath == null) {
            return false;
        }
        Integer share = shareByPath.get(path);
        return share != null && share == 1;
    }

    private static void sortRowsByCapturedTime(List<MediaListAdapter.MediaRow> rows) {
        rows.sort((a, b) -> Long.compare(capturedMs(b), capturedMs(a)));
    }

    private static long capturedMs(MediaListAdapter.MediaRow row) {
        if (row.item != null) {
            long taken = row.item.takenAt;
            if (taken > 0L) {
                return taken;
            }
            return row.item.modifiedAt;
        }
        return 0L;
    }
}
