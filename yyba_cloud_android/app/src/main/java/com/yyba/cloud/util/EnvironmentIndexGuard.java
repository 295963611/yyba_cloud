package com.yyba.cloud.util;

import android.content.Context;

/**
 * 多环境隔离：服务器或账号变化时清空本地云端文件索引，避免混用上一环境的 sync-index。
 */
public final class EnvironmentIndexGuard {

    private EnvironmentIndexGuard() {
    }

    /** 当前环境 Key = 服务器 URL + 登录用户名 */
    public static String buildKey(PrefsManager prefs) {
        return EnvironmentKey.of(prefs.getServerUrl(), prefs.getUsername());
    }

    /**
     * 若环境 Key 与上次不一致，清空云端索引并更新记录。
     *
     * @return 是否执行了清空
     */
    public static boolean applyIfChanged(Context context, PrefsManager prefs) {
        String current = buildKey(prefs);
        if (current.isEmpty()) {
            return false;
        }
        String last = prefs.getLastIndexEnvironmentKey();
        if (last == null || last.isEmpty()) {
            prefs.setLastIndexEnvironmentKey(current);
            return false;
        }
        if (current.equals(last)) {
            return false;
        }
        ServerSyncCache.clearIndexOnlySync(context);
        SharedMediaPrefetchCache.clear();
        prefs.setLastIndexEnvironmentKey(current);
        return true;
    }
}
