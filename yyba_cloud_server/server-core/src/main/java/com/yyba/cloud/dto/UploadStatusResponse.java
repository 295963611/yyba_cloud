package com.yyba.cloud.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UploadStatusResponse {
    private String uploadId;
    private String status;
    private Integer chunkSize;
    private Integer totalChunks;
    private List<Integer> uploadedChunks;
}
