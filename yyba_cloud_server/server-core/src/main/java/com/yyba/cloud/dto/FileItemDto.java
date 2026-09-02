package com.yyba.cloud.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileItemDto {
    private Long id;
    private String name;
    private String relativePath;
    private boolean directory;
    private Long size;
    private String mimeType;
    private String fileType;
    private String thumbnailPath;
    private String createdAt;
    private String deletedAt;
}
