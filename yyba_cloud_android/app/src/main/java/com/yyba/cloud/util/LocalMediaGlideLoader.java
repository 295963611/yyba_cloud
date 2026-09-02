package com.yyba.cloud.util;

import android.content.Context;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.yyba.cloud.R;
import com.yyba.cloud.sync.MediaScanner;

import java.util.List;

/**
 * 手机 Tab 本地媒体缩略图：MediaStore {@code content://} Uri 交给 Glide 加载与缓存。
 */
public final class LocalMediaGlideLoader {

    private static final int THUMB_SIZE = 320;

    private static final RequestOptions THUMB_OPTIONS = new RequestOptions()
            .centerCrop()
            .override(THUMB_SIZE, THUMB_SIZE)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .dontAnimate();

    private LocalMediaGlideLoader() {
    }

    public static void load(Context context, MediaScanner.MediaItem item, ImageView target) {
        if (context == null || item == null || target == null) {
            return;
        }
        Context ctx = context.getApplicationContext();
        if ("AUDIO".equals(item.mediaType)) {
            clear(ctx, target);
            target.setImageResource(R.drawable.ic_audio);
            return;
        }
        if (item.uri == null) {
            return;
        }
        if ("VIDEO".equals(item.mediaType)) {
            Glide.with(ctx)
                    .load(item.uri)
                    .apply(THUMB_OPTIONS)
                    .into(target);
            return;
        }
        Glide.with(ctx)
                .load(item.uri)
                .apply(THUMB_OPTIONS)
                .into(target);
    }

    public static void preload(Context context, List<MediaScanner.MediaItem> items) {
        if (context == null || items == null || items.isEmpty()) {
            return;
        }
        Context app = context.getApplicationContext();
        for (MediaScanner.MediaItem item : items) {
            if (item == null || item.uri == null) {
                continue;
            }
            if (!"IMAGE".equals(item.mediaType) && !"VIDEO".equals(item.mediaType)) {
                continue;
            }
            Glide.with(app)
                    .load(item.uri)
                    .apply(THUMB_OPTIONS)
                    .preload();
        }
    }

    public static void clear(Context context, ImageView target) {
        if (context == null || target == null) {
            return;
        }
        Glide.with(context.getApplicationContext()).clear(target);
    }
}
