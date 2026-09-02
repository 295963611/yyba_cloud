package com.yyba.cloud.discovery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 对已保存或已发现的服务器地址做 health 探测（与 mDNS 扫描超时分离）。
 */
public final class HealthChecker {

    private static final String HEALTH_SERVICE = "yyba-cloud";
    public static final long SAVED_ADDRESS_TIMEOUT_MS = 2000;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(SAVED_ADDRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SAVED_ADDRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();

    private HealthChecker() {
    }

    public static boolean probe(String baseUrl) {
        return probe(baseUrl, SAVED_ADDRESS_TIMEOUT_MS);
    }

    public static boolean probe(String baseUrl, long timeoutMs) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return false;
        }
        OkHttpClient client = timeoutMs == SAVED_ADDRESS_TIMEOUT_MS
                ? CLIENT
                : CLIENT.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
        String url = baseUrl.endsWith("/") ? baseUrl + "api/health" : baseUrl + "/api/health";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return false;
            }
            String body = response.body().string();
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return json.has("service")
                    && HEALTH_SERVICE.equals(json.get("service").getAsString());
        } catch (IOException e) {
            return false;
        }
    }
}
