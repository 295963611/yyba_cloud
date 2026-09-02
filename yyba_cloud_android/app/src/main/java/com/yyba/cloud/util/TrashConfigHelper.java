package com.yyba.cloud.util;

import android.content.Context;

import com.yyba.cloud.api.ApiClient;

/** 回收站保留天数（来自服务端配置） */
public final class TrashConfigHelper {

    private static final int DEFAULT_DAYS = 30;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000;

    private static int cachedDays = DEFAULT_DAYS;
    private static long lastFetchAt = 0;

    private TrashConfigHelper() {
    }

    public static int getRetentionDays(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastFetchAt < CACHE_TTL_MS) {
            return cachedDays;
        }
        try {
            int days = new ApiClient(new PrefsManager(context)).fetchTrashRetentionDays();
            if (days > 0) {
                cachedDays = days;
                lastFetchAt = now;
            }
        } catch (Exception ignored) {
        }
        return cachedDays;
    }

    public static void prefetch(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> getRetentionDays(app), "trash-config-prefetch").start();
    }
}
