package com.yyba.cloud.dto;

import lombok.Data;

@Data
public class VerifySyncItemDto {
    private Long remoteFileId;
    private String localUri;
    private String relativePath;
    private Long fileSize;
    private Long capturedAt;
    private String fileName;
    private String mediaType;
    private Long mediaId;
    /** 本地 SHA-256；有 hash 无 remoteFileId 时优先按 hash 重建 */
    private String fileHash;
}
