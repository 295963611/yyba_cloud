package com.yyba.cloud.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;
import android.widget.ImageView;

import com.yyba.cloud.R;
import com.yyba.cloud.sync.MediaScanner;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ThumbnailLoader {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final int THUMB_SIZE = 320;

    private ThumbnailLoader() {}

    public static void loadFull(Context context, Uri uri, ImageView target) {
        if (ThumbBitmapCache.isPaused()) {
            return;
        }
        target.setTag(uri);
        target.setImageResource(android.R.drawable.ic_menu_gallery);
        EXECUTOR.execute(() -> {
            if (ThumbBitmapCache.isPaused()) {
                return;
            }
            Bitmap bitmap = decodeFull(context, uri);
            postBitmap(target, uri, bitmap, android.R.drawable.ic_menu_gallery);
        });
    }

    public static void prefetchThumbnails(Context context, List<MediaScanner.MediaItem> items) {
        if (context == null || items == null || items.isEmpty()) {
            return;
        }
        Context app = context.getApplicationContext();
        for (MediaScanner.MediaItem item : items) {
            if (item == null || item.uri == null) {
                continue;
            }
            String cacheKey = cacheKey(item);
            if (ThumbBitmapCache.get(cacheKey) != null) {
                continue;
            }
            Bitmap bitmap = null;
            if ("VIDEO".equals(item.mediaType)) {
                bitmap = loadVideoThumb(app, item.uri);
            } else if ("IMAGE".equals(item.mediaType)) {
                bitmap = loadImageThumb(app, item.uri);
            }
            if (bitmap != null) {
                ThumbBitmapCache.put(cacheKey, bitmap);
            }
        }
    }

    private static String cacheKey(MediaScanner.MediaItem item) {
        return "local:" + item.uri;
    }

    public static void load(Context context, MediaScanner.MediaItem item, ImageView target) {
        if (item == null || target == null) {
            return;
        }
        String cacheKey = cacheKey(item);
        Bitmap cached = ThumbBitmapCache.get(cacheKey);
        if (cached != null) {
            target.setTag(item.uri);
            target.setImageBitmap(cached);
            return;
        }
        Object currentTag = target.getTag();
        target.setTag(item.uri);
        if ("VIDEO".equals(item.mediaType)) {
            if (!item.uri.equals(currentTag)) {
                target.setImageDrawable(null);
            }
            target.setImageResource(android.R.drawable.ic_media_play);
            if (ThumbBitmapCache.isPaused()) {
                return;
            }
            EXECUTOR.execute(() -> {
                if (ThumbBitmapCache.isPaused()) {
                    return;
                }
                Bitmap thumb = loadVideoThumb(context, item.uri);
                if (thumb != null) {
                    ThumbBitmapCache.put(cacheKey, thumb);
                }
                postBitmap(target, item.uri, thumb, android.R.drawable.ic_media_play);
            });
            return;
        }
        if (!"IMAGE".equals(item.mediaType)) {
            int icon = "AUDIO".equals(item.mediaType)
                    ? R.drawable.ic_audio
                    : android.R.drawable.ic_menu_save;
            if (!item.uri.equals(currentTag)) {
                target.setImageDrawable(null);
            }
            target.setImageResource(icon);
            return;
        }
        if (!item.uri.equals(currentTag)) {
            target.setImageDrawable(null);
        }
        if (ThumbBitmapCache.isPaused()) {
            return;
        }
        EXECUTOR.execute(() -> {
            if (ThumbBitmapCache.isPaused()) {
                return;
            }
            Bitmap thumb = loadImageThumb(context, item.uri);
            if (thumb != null) {
                ThumbBitmapCache.put(cacheKey, thumb);
            }
            postBitmap(target, item.uri, thumb, 0);
        });
    }

    private static void postBitmap(ImageView target, Uri tag, Bitmap bitmap, int fallback) {
        target.post(() -> {
            if (!tag.equals(target.getTag())) return;
            if (bitmap != null && !bitmap.isRecycled()) {
                target.setImageBitmap(bitmap);
            } else if (fallback != 0) {
                target.setImageResource(fallback);
            } else {
                target.setImageDrawable(null);
            }
        });
    }

    private static Bitmap decodeFull(Context context, Uri uri) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(context.getContentResolver().openInputStream(uri), null, opts);
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = Math.max(1, Math.min(opts.outWidth, opts.outHeight) / 1600);
            return BitmapFactory.decodeStream(context.getContentResolver().openInputStream(uri), null, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap loadImageThumb(Context context, Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.getContentResolver().loadThumbnail(
                        uri, new Size(THUMB_SIZE, THUMB_SIZE), null);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ImageDecoder.Source source = ImageDecoder.createSource(
                        context.getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) ->
                        decoder.setTargetSampleSize(Math.max(1, info.getSize().getWidth() / THUMB_SIZE)));
            }
            return BitmapFactory.decodeStream(context.getContentResolver().openInputStream(uri));
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap loadVideoThumb(Context context, Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return context.getContentResolver().loadThumbnail(
                        uri, new Size(THUMB_SIZE, THUMB_SIZE), null);
            }
            long id = Long.parseLong(uri.getLastPathSegment());
            return MediaStore.Video.Thumbnails.getThumbnail(
                    context.getContentResolver(), id,
                    MediaStore.Video.Thumbnails.MINI_KIND, null);
        } catch (Exception e) {
            return null;
        }
    }
}

