package com.yyba.cloud.util;

import android.content.Context;

/**
 * 进入相册前预拉手机 Tab 首屏列表与缩略图，打开页面时可秒开。
 */
public final class LocalPhoneFirstPageCache {

    private static final Object LOCK = new Object();
    private static String prewarmCategory;
    private static PhoneTabPageLoader.FirstPageSnapshot cached;
    private static boolean inFlight;

    private LocalPhoneFirstPageCache() {
    }

    public static void prewarm(Context context, String category) {
        if (context == null || category == null) {
            return;
        }
        synchronized (LOCK) {
            if (inFlight && category.equals(prewarmCategory)) {
                return;
            }
            inFlight = true;
            prewarmCategory = category;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                PhoneTabPageLoader.FirstPageSnapshot snapshot = PhoneTabPageLoader.loadFirstPage(
                        app, category, SyncVerifyScheduler.PAGE_SIZE);
                LocalMediaGlideLoader.preload(app, snapshot.thumbItems);
                synchronized (LOCK) {
                    if (category.equals(prewarmCategory)) {
                        cached = snapshot;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (LOCK) {
                    inFlight = false;
                }
            }
        }, "phone-first-page-" + category).start();
    }

    public static PhoneTabPageLoader.FirstPageSnapshot take(String category) {
        synchronized (LOCK) {
            if (cached != null && category != null && category.equals(prewarmCategory)) {
                PhoneTabPageLoader.FirstPageSnapshot snapshot = cached;
                cached = null;
                return snapshot;
            }
            return null;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            cached = null;
            prewarmCategory = null;
        }
    }
}
