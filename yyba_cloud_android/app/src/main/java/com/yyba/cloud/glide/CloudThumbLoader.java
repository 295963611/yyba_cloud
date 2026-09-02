package com.yyba.cloud.glide;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import java.io.InputStream;

public final class CloudThumbLoader implements ModelLoader<CloudThumb, InputStream> {

    private final Context context;

    CloudThumbLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(@NonNull CloudThumb model, int width, int height,
                                               @NonNull Options options) {
        if (model.relativePath == null || model.relativePath.isEmpty()) {
            return null;
        }
        return new LoadData<>(new ObjectKey(model), new CloudThumbFetcher(context, model));
    }

    @Override
    public boolean handles(@NonNull CloudThumb model) {
        return model.relativePath != null && !model.relativePath.isEmpty();
    }

    public static final class Factory implements ModelLoaderFactory<CloudThumb, InputStream> {

        private final Context context;

        public Factory(Context context) {
            this.context = context.getApplicationContext();
        }

        @NonNull
        @Override
        public ModelLoader<CloudThumb, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new CloudThumbLoader(context);
        }

        @Override
        public void teardown() {
        }
    }
}
