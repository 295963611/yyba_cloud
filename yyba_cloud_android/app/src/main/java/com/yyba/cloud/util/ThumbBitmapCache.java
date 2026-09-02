package com.yyba.cloud.util;

import android.graphics.Bitmap;
import android.util.LruCache;

/** 列表缩略图 LRU 缓存，滚动时可暂停加载 */
public final class ThumbBitmapCache {

    private static final int MAX_KB = Math.max(512, (int) (Runtime.getRuntime().maxMemory() / 1024 / 16));
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(MAX_KB) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private static volatile boolean paused;

    private ThumbBitmapCache() {
    }

    public static boolean isPaused() {
        return paused;
    }

    public static void setPaused(boolean value) {
        paused = value;
    }

    public static Bitmap get(String key) {
        Bitmap bitmap = CACHE.get(key);
        if (bitmap != null && bitmap.isRecycled()) {
            CACHE.remove(key);
            return null;
        }
        return bitmap;
    }

    public static void put(String key, Bitmap bitmap) {
        if (key == null || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        CACHE.put(key, bitmap);
    }

    public static void clear() {
        CACHE.evictAll();
    }
}
