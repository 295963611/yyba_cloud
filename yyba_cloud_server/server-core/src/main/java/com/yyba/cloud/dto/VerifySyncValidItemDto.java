package com.yyba.cloud.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VerifySyncValidItemDto {
    private Long fileId;
    private String relativePath;
    private Integer share;
}
