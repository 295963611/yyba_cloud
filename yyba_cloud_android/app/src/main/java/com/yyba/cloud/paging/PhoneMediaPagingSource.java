package com.yyba.cloud.paging;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.paging.PagingSource;
import androidx.paging.PagingState;

import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.sync.MediaScanner;
import com.yyba.cloud.ui.MediaListAdapter;
import com.yyba.cloud.util.SyncRecordLookup;
import com.yyba.cloud.util.LocalPhoneFirstPageCache;
import com.yyba.cloud.util.PhoneTabPageLoader;
import com.yyba.cloud.util.ServerSyncCache;
import com.yyba.cloud.util.SyncStatusHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import kotlin.coroutines.Continuation;

public final class PhoneMediaPagingSource extends PagingSource<Integer, MediaListAdapter.MediaRow> {

    public static final int PAGE_SIZE = 50;

    public interface PageLoadedListener {
        void onPageLoaded(List<MediaListAdapter.MediaRow> rows, Map<String, SyncRecord> localIndex);
    }

    private final Context appContext;
    private final String category;
    private final Supplier<Map<String, Integer>> shareByPathSupplier;
    private final PageLoadedListener pageLoadedListener;
    private boolean firstCacheConsumed;

    public PhoneMediaPagingSource(Context context, String category,
                                  Supplier<Map<String, Integer>> shareByPathSupplier,
                                  PageLoadedListener pageLoadedListener) {
        this.appContext = context.getApplicationContext();
        this.category = category;
        this.shareByPathSupplier = shareByPathSupplier;
        this.pageLoadedListener = pageLoadedListener;
    }

    @Nullable
    @Override
    public Integer getRefreshKey(@NonNull PagingState<Integer, MediaListAdapter.MediaRow> state) {
        Integer anchor = state.getAnchorPosition();
        if (anchor == null) {
            return null;
        }
        PagingSource.LoadResult.Page<Integer, MediaListAdapter.MediaRow> page = state.closestPageToPosition(anchor);
        if (page == null) {
            return null;
        }
        return page.getPrevKey() != null ? page.getPrevKey() + PAGE_SIZE : 0;
    }

    @Nullable
    @Override
    public Object load(@NonNull LoadParams<Integer> params,
                       @NonNull Continuation<? super LoadResult<Integer, MediaListAdapter.MediaRow>> continuation) {
        return PagingIoHelper.withIo(() -> loadPage(params), continuation);
    }

    private LoadResult<Integer, MediaListAdapter.MediaRow> loadPage(LoadParams<Integer> params) {
        int offset = params.getKey() != null ? params.getKey() : 0;
        Map<String, Integer> shareByPath = shareByPathSupplier != null
                ? shareByPathSupplier.get() : Map.of();

        if (offset == 0 && !firstCacheConsumed) {
            PhoneTabPageLoader.FirstPageSnapshot snapshot = LocalPhoneFirstPageCache.take(category);
            if (snapshot != null) {
                firstCacheConsumed = true;
                if (pageLoadedListener != null) {
                    pageLoadedListener.onPageLoaded(snapshot.rows, snapshot.localIndex);
                }
                return new LoadResult.Page<>(
                        snapshot.rows,
                        null,
                        snapshot.hasMore ? snapshot.nextOffset : null);
            }
        }

        ArrayList<String> pageUris = new ArrayList<>();
        MediaScanner.MediaPage page = new MediaScanner().scanPage(appContext, category, offset, PAGE_SIZE);
        for (MediaScanner.MediaItem item : page.items) {
            if (item.uri != null) {
                pageUris.add(item.uri.toString());
            }
        }
        Map<String, SyncRecord> localIndex = SyncRecordLookup.loadForPhonePage(appContext, pageUris);
        Map<String, String> canonicalUriByRemote = SyncRecordLookup.buildCanonicalUriByRemotePath(
                localIndex.values());
        ArrayList<MediaListAdapter.MediaRow> rows = new ArrayList<>();
        HashSet<String> seenUris = new HashSet<>();
        HashSet<String> seenRemotePaths = new HashSet<>();

        int itemIndex = 0;
        for (MediaScanner.MediaItem item : page.items) {
            String uriKey = item.uri != null ? item.uri.toString() : null;
            if (uriKey == null || seenUris.contains(uriKey)) {
                itemIndex++;
                continue;
            }
            SyncRecord record = localIndex.get(uriKey);
            if (record != null && record.remotePath != null) {
                String canonical = canonicalUriByRemote.get(record.remotePath);
                if ((canonical != null && !canonical.equals(uriKey))
                        || seenRemotePaths.contains(record.remotePath)
                        || SyncRecordLookup.isDuplicateOfBoundCloudFile(appContext, item, record)) {
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
            int mediaStoreOffset = offset + itemIndex;
            rows.add(MediaListAdapter.MediaRow.local(item, synced, isSharedPath(sharePath, shareByPath),
                    remotePath, mediaStoreOffset));
            itemIndex++;
        }

        if (MediaCategoryHelper.sortByCapturedTime(MediaListAdapter.TAB_PHONE, category)) {
            MediaCategoryHelper.sortRowsByCapturedTime(rows);
        }

        int nextOffset = offset + page.items.size();
        Integer nextKey = page.hasMore ? nextOffset : null;
        if (pageLoadedListener != null) {
            pageLoadedListener.onPageLoaded(rows, localIndex);
        }
        return new LoadResult.Page<>(rows, offset == 0 ? null : offset, nextKey);
    }

    private static boolean isSharedPath(String path, Map<String, Integer> shareByPath) {
        if (path == null || shareByPath == null) {
            return false;
        }
        Integer share = shareByPath.get(path);
        return share != null && share == 1;
    }
}
