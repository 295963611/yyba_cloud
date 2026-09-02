package com.yyba.cloud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SetupRequest {
    @NotBlank(message = "请填写主存储目录")
    private String storageRootPath;

    @NotBlank
    @Size(min = 2, max = 32)
    private String username;

    @NotBlank
    @Size(min = 6, max = 64)
    private String password;

    private String displayName;
}
