package com.yyba.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetupStatusResponse {
    private boolean hasUsers;
    private boolean storageConfigured;
    private String storageRootPath;
}
