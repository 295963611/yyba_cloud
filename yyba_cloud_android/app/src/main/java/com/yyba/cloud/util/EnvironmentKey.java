package com.yyba.cloud.util;

/** 环境 Key 纯逻辑，便于单元测试 */
public final class EnvironmentKey {

    private EnvironmentKey() {
    }

    public static String of(String serverUrl, String username) {
        if (serverUrl == null || serverUrl.isEmpty()
                || username == null || username.isEmpty()) {
            return "";
        }
        return serverUrl + "|" + username;
    }
}
