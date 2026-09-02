package com.yyba.cloud.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class VerifySyncResponse {
    private List<Long> invalidIds;
    private List<VerifySyncValidItemDto> validItems;
    private List<SyncRecordDto> rebuiltRecords;
}
