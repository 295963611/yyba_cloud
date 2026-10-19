package com.yyba.cloud.backup;

import android.net.Uri;

/** 待备份文件项：用户选择后、进入备份引擎前的一条数据（MediaStore 驱动） */
public class BackupItem {

    /** MediaStore content:// URI */
    public final Uri mediaUri;
    /** MediaStore _ID */
    public final long mediaId;
    /** 文件名 */
    public final String fileName;
    /** 文件大小（字节） */
    public final long fileSize;
    /** 媒体类型：IMAGE / VIDEO / AUDIO / DOCUMENT */
    public final String mediaType;
    /** 最后修改时间（epoch ms） */
    public final long lastModified;
    /** 拍摄时间（epoch ms，图片/视频） */
    public final long takenAt;
    /** 云端相对路径：<标准前缀>/phoneBackup/<设备名>/<RELATIVE_PATH>/<fileName>，标准前缀为 photos|videos|audio|uploads/documents */
    public final String relativePath;

    public BackupItem(Uri mediaUri, long mediaId, String fileName, long fileSize,
                      String mediaType, long lastModified, long takenAt, String relativePath) {
        this.mediaUri = mediaUri;
        this.mediaId = mediaId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.mediaType = mediaType;
        this.lastModified = lastModified;
        this.takenAt = takenAt;
        this.relativePath = relativePath;
    }
}
