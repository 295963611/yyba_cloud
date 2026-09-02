package com.yyba.cloud.glide;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.util.PrefsManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CloudThumbFetcher implements DataFetcher<InputStream> {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);

    private final Context context;
    private final CloudThumb model;

    CloudThumbFetcher(Context context, CloudThumb model) {
        this.context = context.getApplicationContext();
        this.model = model;
    }

    @Override
    public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super InputStream> callback) {
        EXECUTOR.execute(() -> {
            try {
                ApiClient api = new ApiClient(new PrefsManager(context));
                byte[] data = api.downloadThumbnailBytes(model.relativePath, model.ownerId);
                if (data == null || data.length == 0) {
                    callback.onLoadFailed(new Exception("empty thumbnail"));
                    return;
                }
                callback.onDataReady(new ByteArrayInputStream(data));
            } catch (Exception e) {
                callback.onLoadFailed(e);
            }
        });
    }

    @Override
    public void cleanup() {
    }

    @Override
    public void cancel() {
    }

    @NonNull
    @Override
    public Class<InputStream> getDataClass() {
        return InputStream.class;
    }

    @NonNull
    @Override
    public DataSource getDataSource() {
        return DataSource.REMOTE;
    }
}
