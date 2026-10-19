package com.yyba.cloud.backup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** 设备名提供者：Build.MODEL 默认值 + SharedPreferences 持久化 + 非法字符清洗 */
public final class DeviceNameProvider {

    private static final String PREFS_NAME = "yyba_cloud_prefs";
    private static final String KEY_DEVICE_NAME = "phone_backup_device_name";
    private static final int MAX_LENGTH = 32;
    private static final String FALLBACK = "Android设备";

    private final SharedPreferences prefs;

    public DeviceNameProvider(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 获取设备名：优先读取持久化值，否则取 Build.MODEL 并清洗后保存 */
    public String getDeviceName() {
        String stored = prefs.getString(KEY_DEVICE_NAME, null);
        if (stored != null && !stored.isEmpty()) {
            return stored;
        }
        String cleaned = clean(Build.MODEL != null ? Build.MODEL : "");
        if (cleaned.isEmpty()) {
            cleaned = FALLBACK;
        }
        prefs.edit().putString(KEY_DEVICE_NAME, cleaned).apply();
        return cleaned;
    }

    /** 设置自定义设备名（清洗后保存） */
    public String setDeviceName(String name) {
        String cleaned = clean(name != null ? name : "");
        if (cleaned.isEmpty()) {
            cleaned = FALLBACK;
        }
        prefs.edit().putString(KEY_DEVICE_NAME, cleaned).apply();
        return cleaned;
    }

    /** 清洗非法字符：/ \ : * ? " < > | 及首尾空格和点 → _，截断 32 字符 */
    static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String result = raw.trim();
        // 替换文件系统非法字符
        result = result.replaceAll("[/\\\\:*?\"<>|]", "_");
        // 去除首尾空格和点（Windows 不允许）
        result = result.replaceAll("^[. ]+|[. ]+$", "");
        // 截断
        if (result.length() > MAX_LENGTH) {
            result = result.substring(0, MAX_LENGTH);
            // 截断后再去尾部空格和点
            result = result.replaceAll("[. ]+$", "");
        }
        return result;
    }
}
