package com.yyba.cloud.util;



import android.graphics.Bitmap;

import android.graphics.BitmapFactory;

import android.os.Handler;

import android.os.Looper;

import android.widget.ImageView;



import com.yyba.cloud.api.ApiClient;



import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;



public final class CloudImageLoader {



    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final int MAX_PREVIEW_SIZE = 1920;

    private static final int MAX_PREVIEW_BYTES = 8 * 1024 * 1024;

    private static final Set<String> THUMB_IN_FLIGHT = ConcurrentHashMap.newKeySet();



    private CloudImageLoader() {}



    public static void loadThumbnail(ApiClient api, String relativePath, ImageView target) {

        loadThumbnail(api, relativePath, null, target);

    }



    public static void loadThumbnail(ApiClient api, String relativePath, Long ownerId, ImageView target) {

        loadThumbnail(api, relativePath, ownerId, target, android.R.drawable.ic_menu_gallery);

    }



    public static void loadThumbnail(ApiClient api, String relativePath, Long ownerId,

                                     ImageView target, int fallbackResId) {

        loadThumbnail(api, relativePath, ownerId, target, fallbackResId, false);

    }

    /** 缩略图失败时回退下载原图采样（回收站等场景） */
    public static void loadThumbnailWithFallback(ApiClient api, String relativePath, Long ownerId,
                                                 ImageView target, int fallbackResId) {
        loadThumbnail(api, relativePath, ownerId, target, fallbackResId, true);
    }

    private static void loadThumbnail(ApiClient api, String relativePath, Long ownerId,
                                      ImageView target, int fallbackResId, boolean allowFileFallback) {

        if (relativePath == null || target == null) return;

        String cacheKey = cacheKey(relativePath, ownerId);

        target.setTag(cacheKey);

        Bitmap cached = ThumbBitmapCache.get(cacheKey);

        if (cached != null) {

            target.setImageBitmap(cached);

            return;

        }

        target.setImageResource(fallbackResId);

        if (ThumbBitmapCache.isPaused()) {

            return;

        }

        if (!THUMB_IN_FLIGHT.add(cacheKey)) {

            return;

        }

        EXECUTOR.execute(() -> {

            try {

                if (ThumbBitmapCache.isPaused()) {

                    return;

                }

                Bitmap bitmap = null;

                try {

                    byte[] data = api.downloadThumbnailBytes(relativePath, ownerId);

                    bitmap = decodeSampled(data, 256, 256);

                } catch (Exception ignored) {

                }

                if (bitmap == null && allowFileFallback) {
                    try {
                        byte[] data = api.downloadFileBytes(relativePath, ownerId, 512 * 1024);
                        bitmap = decodeSampled(data, 256, 256);
                    } catch (Exception ignored) {
                    }
                }

                if (bitmap != null) {

                    ThumbBitmapCache.put(cacheKey, bitmap);

                }

                postBitmap(target, cacheKey, bitmap, fallbackResId);

            } finally {

                THUMB_IN_FLIGHT.remove(cacheKey);

            }

        });

    }



    public static void loadFull(ApiClient api, String relativePath, ImageView target, Runnable onComplete) {

        loadFull(api, relativePath, null, target, onComplete);

    }



    public static void loadFull(ApiClient api, String relativePath, Long ownerId,

                                ImageView target, Runnable onComplete) {

        if (relativePath == null || target == null) {

            if (onComplete != null) MAIN.post(onComplete);

            return;

        }

        String cacheKey = cacheKey(relativePath, ownerId);

        target.setTag(cacheKey);

        target.setImageResource(android.R.drawable.ic_menu_gallery);

        EXECUTOR.execute(() -> {

            Bitmap bitmap = null;

            try {

                byte[] data = api.downloadFileBytes(relativePath, ownerId, MAX_PREVIEW_BYTES);

                bitmap = decodeSampled(data, MAX_PREVIEW_SIZE, MAX_PREVIEW_SIZE);

            } catch (OutOfMemoryError oom) {

                System.gc();

            } catch (Exception ignored) {

            }

            postBitmap(target, cacheKey, bitmap, android.R.drawable.ic_menu_gallery);

            if (onComplete != null) {

                MAIN.post(onComplete);

            }

        });

    }



    private static String cacheKey(String relativePath, Long ownerId) {

        return "cloud:" + relativePath + "#" + (ownerId != null ? ownerId : "self");

    }



    private static void postBitmap(ImageView target, String cacheKey, Bitmap bitmap, int fallbackResId) {

        MAIN.post(() -> {

            if (target.getContext() == null) return;

            if (!cacheKey.equals(target.getTag())) return;

            if (bitmap != null && !bitmap.isRecycled()) {

                target.setImageBitmap(bitmap);

            } else {

                target.setImageResource(fallbackResId);

            }

        });

    }



    private static Bitmap decodeSampled(byte[] data, int reqWidth, int reqHeight) {

        BitmapFactory.Options bounds = new BitmapFactory.Options();

        bounds.inJustDecodeBounds = true;

        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

        BitmapFactory.Options opts = new BitmapFactory.Options();

        opts.inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight);

        opts.inPreferredConfig = Bitmap.Config.RGB_565;

        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);

    }



    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {

        int height = Math.max(1, options.outHeight);

        int width = Math.max(1, options.outWidth);

        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {

            int halfHeight = height / 2;

            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight

                    && (halfWidth / inSampleSize) >= reqWidth) {

                inSampleSize *= 2;

            }

        }

        return Math.max(1, inSampleSize);

    }



    public static void clearCache() {

        THUMB_IN_FLIGHT.clear();

        ThumbBitmapCache.clear();

    }

}


