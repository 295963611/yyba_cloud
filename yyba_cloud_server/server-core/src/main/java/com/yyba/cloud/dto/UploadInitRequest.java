package com.yyba.cloud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class UploadInitRequest {
    @NotBlank
    private String fileName;
    @NotBlank
    private String relativePath;
    @NotNull
    @Positive
    private Long totalSize;
    private String mimeType;
    private String sha256;
    private Long capturedAt;
    private String source = "WEB_UPLOAD";
}
