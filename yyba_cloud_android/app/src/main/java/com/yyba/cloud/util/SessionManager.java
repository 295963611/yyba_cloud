package com.yyba.cloud.util;

import android.content.Context;

import com.yyba.cloud.sync.SyncEngine;

/**
 * 登录态：服务端明确拒绝（401/403）时清除本地 token，并停止后台同步。
 */
public final class SessionManager {

    private SessionManager() {
    }

    public static void invalidateOnAuthFailure(Context context, PrefsManager prefs) {
        if (prefs == null || prefs.getToken() == null) {
            return;
        }
        prefs.clearToken();
        SyncEngine.haltBackgroundSync(context);
    }
}
