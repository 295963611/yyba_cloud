package com.yyba.cloud.util;

import android.content.Context;

import com.yyba.cloud.R;
import com.yyba.cloud.api.ApiClient;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public final class ApiErrorHelper {

    private ApiErrorHelper() {
    }

    public static boolean isRetryableNetworkError(Throwable error) {
        if (error instanceof IOException) {
            return true;
        }
        String msg = error != null ? error.getMessage() : null;
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("timeout")
                || lower.contains("failed to connect")
                || lower.contains("unable to resolve")
                || lower.contains("connection reset")
                || lower.contains("connection abort")
                || lower.contains("broken pipe")
                || msg.contains("服务器无响应");
    }

    public static String userMessage(Context context, Throwable error) {
        if (context == null) {
            return error != null && error.getMessage() != null ? error.getMessage() : "请求失败";
        }
        if (error instanceof ApiClient.AuthFailureException) {
            return error.getMessage() != null
                    ? error.getMessage()
                    : context.getString(R.string.login_expired);
        }
        if (error instanceof UnknownHostException
                || error instanceof ConnectException
                || error instanceof SocketTimeoutException
                || isRetryableNetworkError(error)) {
            return context.getString(R.string.network_error);
        }
        String msg = error != null ? error.getMessage() : null;
        if (msg != null && msg.contains("数据库繁忙")) {
            return msg;
        }
        return msg != null ? msg : context.getString(R.string.network_error);
    }
}
