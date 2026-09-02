package com.yyba.cloud.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AlbumPhotoItemDto {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;
    private String relativePath;
    private String sha256;
    private Long fileSize;
    private String thumbnailPath;
    private Long capturedAt;
    private Integer share;
    /** 字符串序列化，避免前端 JS 大整数精度丢失 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long ownerUserId;
    private String ownerUsername;
    private String ownerDisplayName;
}
