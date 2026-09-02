package com.yyba.cloud.paging;

import androidx.annotation.Nullable;

import com.yyba.cloud.api.AlbumPhotoItem;
import com.yyba.cloud.api.AlbumPhotoPage;

import java.util.List;

/** 云端相册 beforeTime 游标分页的 prev/next key（服务端 captured_at 严格小于 beforeTime） */
final class CloudAlbumPagingKeys {

    private CloudAlbumPagingKeys() {
    }

    @Nullable
    static Long prevKey(@Nullable Long loadKey) {
        return loadKey;
    }

    @Nullable
    static Long nextKey(long beforeTime, @Nullable Long loadKey,
                        AlbumPhotoPage page, @Nullable AlbumPhotoItem tail, int rowCount) {
        if (page == null || !page.hasMore || rowCount <= 0 || tail == null
                || tail.capturedAt == null || tail.capturedAt <= 0L) {
            return null;
        }
        long next = tail.capturedAt;
        if (next >= beforeTime) {
            next = beforeTime - 1L;
        }
        if (loadKey != null && next >= loadKey) {
            next = loadKey - 1L;
        }
        return next > 0L ? next : null;
    }

    /**
     * 家庭/我分享分页游标：优先用过滤后 tail，否则用原始 page.items 最后一条，
     * 防止客户端过滤后 rows 为空时游标不推进或重复同一 beforeTime。
     */
    @Nullable
    static Long nextSharedKey(long beforeTime, @Nullable Long loadKey,
                              AlbumPhotoPage page,
                              @Nullable AlbumPhotoItem filteredTail,
                              @Nullable List<AlbumPhotoItem> rawItems) {
        if (page == null || !page.hasMore) {
            return null;
        }
        AlbumPhotoItem cursorTail = filteredTail;
        if (cursorTail == null || cursorTail.capturedAt == null || cursorTail.capturedAt <= 0L) {
            cursorTail = lastItemWithCapturedAt(rawItems);
        }
        if (cursorTail == null || cursorTail.capturedAt == null || cursorTail.capturedAt <= 0L) {
            return null;
        }
        long next = cursorTail.capturedAt;
        if (next >= beforeTime) {
            next = beforeTime - 1L;
        }
        if (loadKey != null && next >= loadKey) {
            next = loadKey - 1L;
        }
        if (next <= 0L || next >= beforeTime) {
            return null;
        }
        if (loadKey != null && next >= loadKey) {
            return null;
        }
        return next;
    }

    @Nullable
    private static AlbumPhotoItem lastItemWithCapturedAt(@Nullable List<AlbumPhotoItem> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            AlbumPhotoItem item = items.get(i);
            if (item != null && item.capturedAt != null && item.capturedAt > 0L) {
                return item;
            }
        }
        return null;
    }
}
