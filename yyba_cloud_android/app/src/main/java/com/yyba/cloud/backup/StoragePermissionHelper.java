package com.yyba.cloud.backup;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

/** MANAGE_EXTERNAL_STORAGE 权限检查与跳转 */
public final class StoragePermissionHelper {

    private StoragePermissionHelper() {
    }

    /** 是否已获得 MANAGE_EXTERNAL_STORAGE（Android 11+）或旧版 READ_EXTERNAL_STORAGE */
    public static boolean hasPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        // Android 10 及以下走旧权限模型，由 READ_EXTERNAL_STORAGE 覆盖
        return true;
    }

    /** 跳转到系统"所有文件访问权限"设置页 */
    public static void requestPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e) {
                // 兜底：跳转通用所有文件访问权限页
                Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            }
        }
    }
}
