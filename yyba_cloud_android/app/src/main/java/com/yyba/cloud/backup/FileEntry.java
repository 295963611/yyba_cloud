package com.yyba.cloud.backup;

import android.net.Uri;

/** 浏览项：目录节点或文件 */
public class FileEntry {

    public enum BackupStatus { UNCHECKED, BACKED_UP, NOT_BACKED_UP }

    /** 是否为目录节点 */
    public final boolean isDirectory;

    // ---- 目录字段 ----
    /** 完整路径，如 "DCIM/Camera"（仅目录项） */
    public final String dirFullPath;
    /** 子目录数（仅目录项） */
    public final int childDirCount;
    /** 该目录下（含子目录）总文件数（仅目录项） */
    public final int totalFileCount;

    // ---- 文件字段 ----
    public final Uri mediaUri;
    public final long mediaId;
    public final String name;
    public final long size;
    public final String mediaType;
    public final long modifiedAt;
    public final long takenAt;
    public final String relativePath;

    public BackupStatus backupStatus = BackupStatus.UNCHECKED;
    public String backupSource;

    private FileEntry(boolean isDirectory, String dirFullPath, int childDirCount, int totalFileCount,
                      Uri mediaUri, long mediaId, String name, long size,
                      String mediaType, long modifiedAt, long takenAt, String relativePath) {
        this.isDirectory = isDirectory;
        this.dirFullPath = dirFullPath;
        this.childDirCount = childDirCount;
        this.totalFileCount = totalFileCount;
        this.mediaUri = mediaUri;
        this.mediaId = mediaId;
        this.name = name;
        this.size = size;
        this.mediaType = mediaType;
        this.modifiedAt = modifiedAt;
        this.takenAt = takenAt;
        this.relativePath = relativePath;
    }

    public static FileEntry directory(LocalFileBrowser.DirectoryNode node) {
        return new FileEntry(true, node.fullPath, node.children.size(), node.totalFileCount,
                null, 0L, node.name, 0L, null, 0L, 0L, null);
    }

    public static FileEntry group(LocalFileBrowser.FileGroup group) {
        String displayName = group.relativePath.replace("/", " / ");
        return new FileEntry(true, group.relativePath, 0, group.fileCount,
                null, 0L, displayName, 0L, null, 0L, 0L, null);
    }

    public static FileEntry file(LocalFileBrowser.MediaFile mf) {
        return new FileEntry(false, null, 0, 0,
                mf.uri, mf.mediaId, mf.fileName, mf.fileSize,
                mf.mediaType, mf.modifiedAt, mf.takenAt, mf.relativePath);
    }

    public boolean isGroup() { return isDirectory; }
    public boolean isDir() { return isDirectory; }
    public boolean canRead() { return !isDirectory; }

    public String getSelectionKey() {
        if (isDirectory) return "dir:" + dirFullPath;
        return mediaUri != null ? mediaUri.toString() : name;
    }

    public boolean isBackedUp() { return backupStatus == BackupStatus.BACKED_UP; }
}
