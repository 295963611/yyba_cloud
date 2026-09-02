package com.yyba.cloud.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SyncRecordDto {
    private String localUri;
    private Long mediaId;
    private String fileName;
    private Long fileSize;
    private String fileHash;
    private String mediaType;
    private Long modifiedAt;
    private String remotePath;
    private Long remoteFileId;
    private Integer share;
}
