package com.yyba.cloud.glide;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;

@GlideModule
public final class YybaGlideModule extends AppGlideModule {

    @Override
    public void registerComponents(@NonNull Context context, @NonNull Glide glide,
                                   @NonNull Registry registry) {
        registry.append(CloudThumb.class, InputStream.class, new CloudThumbLoader.Factory(context));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
