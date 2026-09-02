package com.yyba.cloud.dto;

import lombok.Data;

@Data
public class ConfigUpdateRequest {
    private Integer port;
    private String storageRootPath;
    private Boolean backupEnabled;
    private String backupPath;
    private Integer chunkSize;
    private Integer trashRetentionDays;
}
