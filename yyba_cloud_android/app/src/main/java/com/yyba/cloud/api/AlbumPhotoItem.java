package com.yyba.cloud.api;

public class AlbumPhotoItem {
    /** 云端 files 表主键，与 {@link com.yyba.cloud.db.SyncRecord#remoteFileId} 对应 */
    public Long fileId;
    public String relativePath;
    public String sha256;
    public Long fileSize;
    public String thumbnailPath;
    public Long capturedAt;
    public Integer share;
    /** 服务端 JSON 为字符串，避免 JS 大整数精度丢失 */
    public String ownerUserId;
    public String ownerUsername;
    public String ownerDisplayName;

    public Long ownerIdAsLong() {
        if (ownerUserId == null || ownerUserId.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(ownerUserId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
