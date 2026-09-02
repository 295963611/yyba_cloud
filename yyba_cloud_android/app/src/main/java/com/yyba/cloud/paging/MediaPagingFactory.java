package com.yyba.cloud.paging;

import android.content.Context;

import androidx.paging.PagingSource;

import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.ui.MediaListAdapter;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class MediaPagingFactory {

    public static final int PAGE_SIZE = PhoneMediaPagingSource.PAGE_SIZE;

    private MediaPagingFactory() {
    }

    public static PagingSource<Integer, MediaListAdapter.MediaRow> createPhone(
            Context context,
            String category,
            Supplier<Map<String, Integer>> shareByPathSupplier,
            PhoneMediaPagingSource.PageLoadedListener listener) {
        return new PhoneMediaPagingSource(context, category, shareByPathSupplier, listener);
    }

    public static PagingSource<Long, MediaListAdapter.MediaRow> createCloud(
            Context context, ApiClient apiClient, String category) {
        return new CloudMediaPagingSource(context, apiClient, category);
    }

    public static PagingSource<SharedLoadKey, MediaListAdapter.MediaRow> createShared(
            Context context, ApiClient apiClient, String category, String tabType) {
        String scope = MediaListAdapter.TAB_FAMILY.equals(tabType) ? "family" : "mine";
        boolean myShareTab = MediaListAdapter.TAB_MY_SHARE.equals(tabType);
        return new SharedMediaPagingSource(context, apiClient, category, scope, myShareTab);
    }
}
