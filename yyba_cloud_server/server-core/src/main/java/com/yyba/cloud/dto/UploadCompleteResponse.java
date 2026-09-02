package com.yyba.cloud.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UploadCompleteResponse {
    private Long fileId;
    private String relativePath;
    private Long fileSize;
}
