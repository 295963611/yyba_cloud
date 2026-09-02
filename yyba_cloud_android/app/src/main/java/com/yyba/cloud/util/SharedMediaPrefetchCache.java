package com.yyba.cloud.util;

import android.content.Context;

import com.yyba.cloud.api.AlbumPhotoPage;
import com.yyba.cloud.api.ApiClient;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 进入相册页面前预拉「家庭 / 我分享」首屏，切换 Tab 时可秒开。
 */
public final class SharedMediaPrefetchCache {

    private static final Object LOCK = new Object();
    private static final Map<String, AlbumPhotoPage> pages = new HashMap<>();
    private static final Set<String> inFlight = new HashSet<>();

    private SharedMediaPrefetchCache() {
    }

    private static String key(String category, String scope) {
        return category + "|" + scope;
    }

    public static void prewarm(Context context, String category) {
        if (category == null) {
            return;
        }
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (inFlight.contains(category)) {
                return;
            }
            inFlight.add(category);
        }
        new Thread(() -> {
            try {
                ApiClient api = new ApiClient(new PrefsManager(app));
                long beforeTime = System.currentTimeMillis();
                prefetch(category, "family", beforeTime, api);
                prefetch(category, "mine", beforeTime, api);
            } finally {
                synchronized (LOCK) {
                    inFlight.remove(category);
                }
            }
        }, "shared-prefetch-" + category).start();
    }

    private static void prefetch(String category, String scope, long beforeTime, ApiClient api) {
        String cacheKey = key(category, scope);
        synchronized (LOCK) {
            if (pages.containsKey(cacheKey)) {
                return;
            }
        }
        try {
            AlbumPhotoPage page = api.fetchSharedMedia(scope, fileTypeForCategory(category), beforeTime, 48);
            if (page == null) {
                return;
            }
            synchronized (LOCK) {
                pages.put(cacheKey, page);
            }
        } catch (Exception ignored) {
        }
    }

    public static AlbumPhotoPage take(String category, String scope) {
        synchronized (LOCK) {
            return pages.remove(key(category, scope));
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            pages.clear();
        }
    }

    private static String fileTypeForCategory(String category) {
        if ("VIDEO".equals(category)) {
            return "VIDEO";
        }
        if ("AUDIO".equals(category)) {
            return "AUDIO";
        }
        if ("DOCUMENT".equals(category)) {
            return "DOCUMENT";
        }
        return "PHOTO";
    }
}
