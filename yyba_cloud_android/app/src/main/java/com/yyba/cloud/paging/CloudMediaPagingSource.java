package com.yyba.cloud.paging;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.paging.PagingSource;
import androidx.paging.PagingState;

import com.yyba.cloud.api.AlbumPhotoItem;
import com.yyba.cloud.api.AlbumPhotoPage;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.ui.MediaListAdapter;
import com.yyba.cloud.util.SyncRecordLookup;

import java.util.ArrayList;

import kotlin.coroutines.Continuation;

public final class CloudMediaPagingSource extends PagingSource<Long, MediaListAdapter.MediaRow> {

    public static final int PAGE_SIZE = 50;

    private final Context appContext;
    private final ApiClient apiClient;
    private final String category;

    public CloudMediaPagingSource(Context context, ApiClient apiClient, String category) {
        this.appContext = context.getApplicationContext();
        this.apiClient = apiClient;
        this.category = category;
    }

    @Override
    public boolean getKeyReuseSupported() {
        return true;
    }

    @Nullable
    @Override
    public Long getRefreshKey(@NonNull PagingState<Long, MediaListAdapter.MediaRow> state) {
        return null;
    }

    @Nullable
    @Override
    public Object load(@NonNull LoadParams<Long> params,
                       @NonNull Continuation<? super LoadResult<Long, MediaListAdapter.MediaRow>> continuation) {
        return PagingIoHelper.withIo(() -> loadPage(params), continuation);
    }

    private LoadResult<Long, MediaListAdapter.MediaRow> loadPage(LoadParams<Long> params) throws Exception {
        Long loadKey = params.getKey();
        long beforeTime = loadKey != null ? loadKey : System.currentTimeMillis();
        ArrayList<MediaListAdapter.MediaRow> rows = new ArrayList<>();
        try {
            AlbumPhotoPage page = apiClient.fetchAlbumPhotos(
                    MediaCategoryHelper.fileTypeForApi(category), beforeTime, PAGE_SIZE);
            if (page == null || page.items == null || page.items.isEmpty()) {
                return new LoadResult.Page<>(rows, CloudAlbumPagingKeys.prevKey(loadKey), null);
            }
            SyncRecordLookup.CloudPageLookup lookup = SyncRecordLookup.loadForCloudPage(
                    appContext, page.items);
            AlbumPhotoItem tail = null;
            for (AlbumPhotoItem item : page.items) {
                if (item.relativePath == null
                        || !MediaCategoryHelper.matchesCategoryPath(category, item.relativePath)) {
                    continue;
                }
                SyncRecordLookup.DownloadPatch patch = lookup.resolveDownload(item);
                rows.add(MediaListAdapter.MediaRow.cloudFromPatch(item, patch));
                tail = item;
            }
            Long nextKey = CloudAlbumPagingKeys.nextKey(beforeTime, loadKey, page, tail, rows.size());
            return new LoadResult.Page<>(rows, CloudAlbumPagingKeys.prevKey(loadKey), nextKey);
        } catch (Exception e) {
            return new LoadResult.Error<>(e);
        }
    }
}
