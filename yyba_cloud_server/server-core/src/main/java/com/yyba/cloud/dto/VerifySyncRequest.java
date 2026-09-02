package com.yyba.cloud.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class VerifySyncRequest {

    @Size(max = 50)
    private List<VerifySyncItemDto> items;

    /** @deprecated 兼容旧客户端，仅传 fileId */
    @Size(max = 100)
    private List<Long> fileIds;
}
