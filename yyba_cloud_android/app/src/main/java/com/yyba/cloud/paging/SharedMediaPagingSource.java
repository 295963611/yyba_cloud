package com.yyba.cloud.paging;



import android.content.Context;



import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import androidx.paging.PagingSource;

import androidx.paging.PagingState;



import com.yyba.cloud.api.AlbumPhotoItem;

import com.yyba.cloud.api.AlbumPhotoPage;

import com.yyba.cloud.api.ApiClient;

import com.yyba.cloud.api.SyncIndexItem;

import com.yyba.cloud.ui.MediaListAdapter;

import com.yyba.cloud.util.ServerSyncCache;

import com.yyba.cloud.util.SharedMediaPrefetchCache;

import com.yyba.cloud.util.SyncRecordLookup;



import java.util.ArrayList;

import java.util.List;



import kotlin.coroutines.Continuation;



public final class SharedMediaPagingSource extends PagingSource<SharedLoadKey, MediaListAdapter.MediaRow> {



    public static final int PAGE_SIZE = 50;



    private final Context appContext;

    private final ApiClient apiClient;

    private final String category;

    private final String scope;

    private final boolean myShareTab;

    private boolean firstCacheConsumed;

    private boolean syncIndexMode;

    private List<SyncIndexItem> filteredCloudItems;



    public SharedMediaPagingSource(Context context, ApiClient apiClient, String category,

                                   String scope, boolean myShareTab) {

        this.appContext = context.getApplicationContext();

        this.apiClient = apiClient;

        this.category = category;

        this.scope = scope;

        this.myShareTab = myShareTab;

    }



    @Override

    public boolean getKeyReuseSupported() {

        return true;

    }



    @Nullable

    @Override

    public SharedLoadKey getRefreshKey(@NonNull PagingState<SharedLoadKey, MediaListAdapter.MediaRow> state) {

        return null;

    }



    @Nullable

    @Override

    public Object load(@NonNull LoadParams<SharedLoadKey> params,

                       @NonNull Continuation<? super LoadResult<SharedLoadKey, MediaListAdapter.MediaRow>> continuation) {

        return PagingIoHelper.withIo(() -> loadPage(params), continuation);

    }



    private LoadResult<SharedLoadKey, MediaListAdapter.MediaRow> loadPage(LoadParams<SharedLoadKey> params)

            throws Exception {

        SharedLoadKey key = params.getKey() != null ? params.getKey() : SharedLoadKey.firstApi();

        if (key.syncIndexMode || syncIndexMode) {

            syncIndexMode = true;

            return loadSyncIndexPage(key.syncOffset);

        }

        boolean isFirstPage = params.getKey() == null;

        try {

            AlbumPhotoPage page = null;

            if (isFirstPage && !firstCacheConsumed) {

                page = SharedMediaPrefetchCache.take(category, scope);

                if (page != null) {

                    firstCacheConsumed = true;

                }

            }

            if (page == null) {

                page = apiClient.fetchSharedMedia(scope, MediaCategoryHelper.fileTypeForApi(category),

                        key.beforeTime, PAGE_SIZE);

            }

            SharedLoadKey prevKey = isFirstPage ? null : key;

            if (page == null || page.items == null || page.items.isEmpty()) {

                return new LoadResult.Page<>(List.of(), prevKey, null);

            }

            SyncRecordLookup.CloudPageLookup lookup = SyncRecordLookup.loadForCloudPage(

                    appContext, page.items);

            ArrayList<MediaListAdapter.MediaRow> rows = new ArrayList<>();

            AlbumPhotoItem tail = null;

            for (AlbumPhotoItem item : page.items) {

                if (item.relativePath == null || !MediaCategoryHelper.matchesCategoryPath(category, item.relativePath)) {

                    continue;

                }

                rows.add(MediaListAdapter.MediaRow.sharedFromPatch(item, lookup.resolveDownload(item)));

                tail = item;

            }

            Long nextBefore = CloudAlbumPagingKeys.nextSharedKey(

                    key.beforeTime, isFirstPage ? null : key.beforeTime, page, tail, page.items);

            SharedLoadKey nextKey = nextBefore != null ? new SharedLoadKey(nextBefore, 0, false) : null;

            return new LoadResult.Page<>(rows, prevKey, nextKey);

        } catch (Exception e) {

            if ("AUDIO".equals(category) && myShareTab) {

                syncIndexMode = true;

                return loadSyncIndexPage(0);

            }

            return new LoadResult.Error<>(e);

        }

    }



    private LoadResult<SharedLoadKey, MediaListAdapter.MediaRow> loadSyncIndexPage(int offset) {

        String prefix = MediaCategoryHelper.pathPrefixForCategory(category);

        if (prefix == null) {

            return new LoadResult.Page<>(List.of(), null, null);

        }

        if (filteredCloudItems == null) {

            filteredCloudItems = new ArrayList<>();

            List<SyncIndexItem> source = ServerSyncCache.peekSyncIndexItems();

            for (SyncIndexItem item : source) {

                if (item.relativePath == null

                        || !MediaCategoryHelper.matchesSyncIndexCategory(category, item.relativePath, prefix)

                        || item.share == null || item.share != 1) {

                    continue;

                }

                filteredCloudItems.add(item);

            }

            filteredCloudItems.sort((a, b) -> Long.compare(

                    MediaCategoryHelper.capturedMs(b), MediaCategoryHelper.capturedMs(a)));

        }

        int end = Math.min(offset + PAGE_SIZE, filteredCloudItems.size());

        ArrayList<AlbumPhotoItem> pageItems = new ArrayList<>();

        for (int i = offset; i < end; i++) {

            pageItems.add(MediaCategoryHelper.toAlbumPhotoItem(filteredCloudItems.get(i)));

        }

        SyncRecordLookup.CloudPageLookup lookup = SyncRecordLookup.loadForCloudPage(appContext, pageItems);

        ArrayList<MediaListAdapter.MediaRow> rows = new ArrayList<>();

        for (AlbumPhotoItem item : pageItems) {

            rows.add(MediaListAdapter.MediaRow.sharedFromPatch(item, lookup.resolveDownload(item)));

        }

        SharedLoadKey nextKey = end < filteredCloudItems.size()

                ? new SharedLoadKey(0, end, true) : null;

        SharedLoadKey prevKey = offset > 0 ? new SharedLoadKey(0, Math.max(0, offset - PAGE_SIZE), true) : null;

        return new LoadResult.Page<>(rows, prevKey, nextKey);

    }

}


