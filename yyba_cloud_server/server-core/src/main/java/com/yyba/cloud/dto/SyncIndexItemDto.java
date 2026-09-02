package com.yyba.cloud.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SyncIndexItemDto {
    private String relativePath;
    private String sha256;
    private Long fileSize;
    private String fileType;
    private String thumbnailPath;
    private Integer share;
    private Long capturedAt;
    /** 最后更新时间（epoch ms），供增量同步游标 */
    private Long updatedAt;
}
