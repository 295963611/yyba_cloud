package com.yyba.cloud.backup;

import android.content.Context;
import android.net.Uri;

import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.db.SyncRecordDao;

/** 备份状态解析器：Tier 0 localUri 精确匹配 + Tier 2 网络兜底 */
public class BackupStatusResolver {

    private final SyncRecordDao dao;

    public BackupStatusResolver(Context context) {
        this.dao = AppDatabase.getInstance(context).syncRecordDao();
        // 一次性清理旧格式 localUri 脏数据（MediaStore 迁移遗留）
        try {
            int deleted = dao.deleteLegacyFileUris();
            if (deleted > 0) {
                android.util.Log.d("BackupStatusResolver",
                        "Cleaned " + deleted + " legacy SyncRecord rows with file:// or /storage/ URIs");
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Tier 0: 按 localUri 精确匹配。
     * MediaStore 路线下，相册同步和手机备份都用 content://media/...，
     * 两侧 SyncRecord.localUri 格式统一，一次命中跨入口。
     */
    public FileEntry.BackupStatus resolveLocal(String localUri) {
        if (localUri == null || localUri.isEmpty()) {
            return FileEntry.BackupStatus.UNCHECKED;
        }
        SyncRecord record = dao.findByLocalUri(localUri);
        if (record != null && record.remoteFileId != null && record.remoteFileId > 0) {
            return FileEntry.BackupStatus.BACKED_UP;
        }
        return FileEntry.BackupStatus.UNCHECKED;
    }

    /** 批量填充浏览项状态（仅文件，跳过分组项） */
    public void fillStatus(java.util.List<FileEntry> entries) {
        if (entries == null) return;
        for (FileEntry entry : entries) {
            if (entry.isDirectory) continue;
            String uri = entry.mediaUri != null ? entry.mediaUri.toString() : null;
            entry.backupStatus = resolveLocal(uri);
        }
    }

    /**
     * 判断远程路径来源：
     * - 含 /phoneBackup/ 段 → 手机备份（新格式：photos|videos|audio|uploads/documents/phoneBackup/...
     *   旧格式兼容：顶层 phoneBackup/...）
     * - photos|videos|audio|uploads/documents 顶层 → 相册同步
     */
    public static String resolveSource(String remotePath) {
        if (remotePath == null || remotePath.isEmpty()) {
            return null;
        }
        // 手机备份：新格式（标准前缀下的 phoneBackup/ 子目录）+ 旧格式（顶层 phoneBackup/）
        if (remotePath.startsWith("phoneBackup/") || remotePath.contains("/phoneBackup/")) {
            return "phoneBackup";
        }
        if (remotePath.startsWith("photos/") || remotePath.startsWith("videos/")
                || remotePath.startsWith("audio/") || remotePath.startsWith("uploads/documents/")) {
            return "album";
        }
        return "other";
    }
}
