package com.yyba.cloud.util;

import android.content.Context;
import android.os.Looper;

import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.api.SyncIndexItem;
import com.yyba.cloud.api.SyncIndexPage;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.util.CloudImageLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ServerSyncCache {

    private static final Object LOCK = new Object();
    private static final Object REFRESH_LOCK = new Object();
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "server-sync-cache");
        t.setDaemon(true);
        return t;
    });
    private static final int BOOTSTRAP_PAGE_SIZE = 500;

    private static Set<String> syncedPaths = new HashSet<>();
    private static List<SyncIndexItem> syncIndexItems = new ArrayList<>();
    private static long lastFetchAt = 0;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    /** 为 false 时不再按 TTL 后台拉 sync-index，仅显式 refresh* 才联网 */
    private static final boolean AUTO_REFRESH_ENABLED = false;

    private static Context pendingRefreshApp;
    private static boolean pendingForceFull;
    private static boolean refreshScheduled;

    private ServerSyncCache() {}

    public static Set<String> getSyncedPaths(Context context, boolean forceRefresh) {
        ensureFresh(context, forceRefresh);
        synchronized (LOCK) {
            return new HashSet<>(syncedPaths);
        }
    }

    public static List<SyncIndexItem> getSyncIndexItems(Context context, boolean forceRefresh) {
        ensureFresh(context, forceRefresh);
        synchronized (LOCK) {
            return new ArrayList<>(syncIndexItems);
        }
    }

    public static Map<String, Integer> getShareByPath(Context context, boolean forceRefresh) {
        ensureFresh(context, forceRefresh);
        return peekShareByPath();
    }

    public static boolean hasCachedIndex() {
        synchronized (LOCK) {
            return !syncedPaths.isEmpty();
        }
    }

    public static Set<String> peekSyncedPaths() {
        synchronized (LOCK) {
            return new HashSet<>(syncedPaths);
        }
    }

    public static Map<String, Integer> peekShareByPath() {
        synchronized (LOCK) {
            Map<String, Integer> map = new HashMap<>();
            for (SyncIndexItem item : syncIndexItems) {
                if (item.relativePath != null && item.share != null) {
                    map.put(item.relativePath, item.share);
                }
            }
            return map;
        }
    }

    public static List<SyncIndexItem> peekSyncIndexItems() {
        synchronized (LOCK) {
            return new ArrayList<>(syncIndexItems);
        }
    }

    /** 仅从 Room 加载到内存，不发起 sync-index 网络请求 */
    public static void warmUp(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        REFRESH_EXECUTOR.execute(() -> {
            synchronized (LOCK) {
                if (syncedPaths.isEmpty()) {
                    loadMemoryFromRoom(app);
                }
            }
        });
    }

    /** @deprecated 客户端已不再拉 sync-index；保留空实现避免旧调用点触发网络 */
    @Deprecated
    public static void refreshAfterLogin(Context context) {
    }

    /** @deprecated 见 {@link #refreshAfterLogin} */
    @Deprecated
    public static void refreshAsync(Context context) {
    }

    /** @deprecated 见 {@link #refreshAfterLogin} */
    @Deprecated
    public static void refreshSync(Context context) {
    }

    /** 删除后立即从本地云端索引剔除，避免相册/视频仍展示已进回收站的项 */
    public static void removePaths(Context context, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        removePathsFromMemory(paths);
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        Runnable work = () -> {
            CloudIndexStore.merge(app, null, paths);
            loadMemoryFromRoom(app);
        };
        if (isMainThread()) {
            REFRESH_EXECUTOR.execute(work);
        } else {
            work.run();
        }
    }

    private static void removePathsFromMemory(List<String> paths) {
        Set<String> toRemove = new HashSet<>(paths);
        synchronized (LOCK) {
            syncedPaths.removeAll(toRemove);
            for (int i = syncIndexItems.size() - 1; i >= 0; i--) {
                SyncIndexItem item = syncIndexItems.get(i);
                if (item.relativePath != null && toRemove.contains(item.relativePath)) {
                    syncIndexItems.remove(i);
                }
            }
        }
    }

    public static void removePath(Context context, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        removePaths(context, java.util.Collections.singletonList(path));
    }

    /** 分享/取消分享后立即更新内存索引中的 share 字段 */
    public static void applySharePaths(List<String> paths, int share) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        Set<String> targets = new HashSet<>(paths);
        synchronized (LOCK) {
            for (SyncIndexItem item : syncIndexItems) {
                if (item.relativePath != null && targets.contains(item.relativePath)) {
                    item.share = share;
                }
            }
        }
    }

    private static void scheduleRefresh(Context app, boolean forceFull, boolean blocking) {
        Future<?> future;
        synchronized (REFRESH_LOCK) {
            pendingRefreshApp = app;
            if (forceFull) {
                pendingForceFull = true;
            }
            if (!refreshScheduled) {
                refreshScheduled = true;
                future = REFRESH_EXECUTOR.submit(ServerSyncCache::drainRefreshQueue);
            } else {
                future = null;
            }
        }
        if (blocking && future != null) {
            awaitFuture(future);
        } else if (blocking) {
            awaitRefreshDone();
        }
    }

    private static void awaitFuture(Future<?> future) {
        try {
            future.get(120, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static void awaitRefreshDone() {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            synchronized (REFRESH_LOCK) {
                if (!refreshScheduled) {
                    return;
                }
            }
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void drainRefreshQueue() {
        try {
            while (true) {
                Context app;
                boolean forceFull;
                synchronized (REFRESH_LOCK) {
                    app = pendingRefreshApp;
                    forceFull = pendingForceFull;
                    pendingRefreshApp = null;
                    pendingForceFull = false;
                    if (app == null) {
                        refreshScheduled = false;
                        return;
                    }
                }
                refreshBlocking(app, forceFull);
                synchronized (REFRESH_LOCK) {
                    if (pendingRefreshApp == null) {
                        refreshScheduled = false;
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            synchronized (REFRESH_LOCK) {
                refreshScheduled = false;
            }
        }
    }

    private static void ensureFresh(Context context, boolean forceRefresh) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (syncedPaths.isEmpty()) {
                loadMemoryFromRoom(app);
            }
        }
        if (!forceRefresh) {
            return;
        }
        if (isMainThread()) {
            scheduleRefresh(app, true, false);
            return;
        }
        scheduleRefresh(app, true, true);
    }

    private static boolean isStale(boolean forceRefresh) {
        if (forceRefresh) {
            return true;
        }
        if (!AUTO_REFRESH_ENABLED) {
            return false;
        }
        synchronized (LOCK) {
            return syncedPaths.isEmpty()
                    || System.currentTimeMillis() - lastFetchAt >= CACHE_TTL_MS;
        }
    }

    private static void refreshBlocking(Context context, boolean forceFull) {
        Context app = context.getApplicationContext();
        try {
            PrefsManager prefs = new PrefsManager(app);
            ApiClient api = new ApiClient(prefs);

            if (forceFull) {
                CloudIndexStore.clear(app);
                prefs.setSyncIndexServerTime(0L);
            }

            Long since = resolveSince(app, prefs, forceFull);
            long lastServerTime = 0;
            if (since != null && since > 0) {
                SyncIndexPage page = api.fetchSyncIndexPage(since);
                applySyncPage(app, page, false);
                lastServerTime = page.serverTime;
            } else {
                String cursor = null;
                boolean firstPage = true;
                do {
                    SyncIndexPage page = api.fetchSyncIndexPage(null, cursor, BOOTSTRAP_PAGE_SIZE);
                    applySyncPage(app, page, firstPage && forceFull);
                    firstPage = false;
                    lastServerTime = page.serverTime;
                    cursor = page.hasMore ? page.nextCursor : null;
                } while (cursor != null && !cursor.isEmpty());
            }

            if (lastServerTime > 0) {
                prefs.setSyncIndexServerTime(lastServerTime);
            }
            loadMemoryFromRoom(app);
        } catch (Exception ignored) {
        }
    }

    /**
     * 优先增量：本地 cloud_index 非空且存在有效 serverTime 时用 since 拉增量。
     * forceFull、首次安装、登出/环境切换后走分页 bootstrap（不传 since）。
     */
    private static Long resolveSince(Context app, PrefsManager prefs, boolean forceFull) {
        if (forceFull) {
            return null;
        }
        if (CloudIndexStore.count(app) <= 0) {
            return null;
        }
        long serverTime = prefs.getSyncIndexServerTime();
        if (serverTime <= 0) {
            return null;
        }
        long localMax = CloudIndexStore.maxUpdatedAt(app);
        long since = Math.max(localMax, serverTime);
        return since > 0 ? since : null;
    }

    private static void applySyncPage(Context app, SyncIndexPage page, boolean replaceAll) {
        if (page == null) {
            return;
        }
        if (replaceAll || page.fullSnapshot) {
            if (replaceAll) {
                CloudIndexStore.replaceAll(app, page.items);
            } else {
                CloudIndexStore.merge(app, page.items, page.removedPaths);
            }
        } else {
            CloudIndexStore.merge(app, page.items, page.removedPaths);
        }
    }

    private static void loadMemoryFromRoom(Context app) {
        List<SyncIndexItem> items = CloudIndexStore.loadAll(app);
        Set<String> paths = new HashSet<>();
        List<SyncIndexItem> copy = new ArrayList<>();
        for (SyncIndexItem item : items) {
            if (item.relativePath != null) {
                paths.add(item.relativePath);
                copy.add(item);
            }
        }
        synchronized (LOCK) {
            syncedPaths = paths;
            syncIndexItems = copy;
            lastFetchAt = System.currentTimeMillis();
        }
    }

    public static void clear(Context context) {
        if (context != null) {
            Context app = context.getApplicationContext();
            REFRESH_EXECUTOR.execute(() -> clearBlocking(app));
        } else {
            clearMemory();
        }
    }

    /** 同步清空云端文件索引（Room + 增量时间戳 + 内存），不清理登录态；主线程会委托后台执行并等待完成 */
    public static void clearIndexOnlySync(Context context) {
        clearEnvironmentDataSync(context);
    }

    /** 环境切换：清空索引、同步映射、上传任务与相关缓存 */
    public static void clearEnvironmentDataSync(Context context) {
        if (context == null) {
            clearMemory();
            CloudImageLoader.clearCache();
            SharedMediaPrefetchCache.clear();
            return;
        }
        Context app = context.getApplicationContext();
        if (isMainThread()) {
            Future<?> future = REFRESH_EXECUTOR.submit(() -> clearEnvironmentBlocking(app));
            awaitFuture(future);
        } else {
            clearEnvironmentBlocking(app);
        }
    }

    /** 登出：同步清空全部本地业务数据 */
    public static void clearAllSync(Context context) {
        clearEnvironmentDataSync(context);
    }

    /** 异步清空云端文件索引 */
    public static void clearIndexOnly(Context context) {
        if (context == null) {
            clearMemory();
            return;
        }
        Context app = context.getApplicationContext();
        if (isMainThread()) {
            REFRESH_EXECUTOR.execute(() -> clearBlocking(app));
        } else {
            clearBlocking(app);
        }
    }

    private static void clearBlocking(Context app) {
        clearEnvironmentBlocking(app);
    }

    private static void clearEnvironmentBlocking(Context app) {
        CloudIndexStore.clear(app);
        AppDatabase db = AppDatabase.getInstance(app);
        db.syncRecordDao().deleteAll();
        db.uploadTaskDao().deleteAll();
        new PrefsManager(app).setSyncIndexServerTime(0L);
        clearMemory();
        CloudImageLoader.clearCache();
        SharedMediaPrefetchCache.clear();
        LocalPhoneFirstPageCache.clear();
    }

    private static void clearMemory() {
        synchronized (LOCK) {
            syncedPaths.clear();
            syncIndexItems.clear();
            lastFetchAt = 0;
        }
        ThumbBitmapCache.clear();
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
