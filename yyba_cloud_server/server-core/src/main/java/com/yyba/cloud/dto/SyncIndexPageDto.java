package com.yyba.cloud.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SyncIndexPageDto {
    private List<SyncIndexItemDto> items;
    private List<String> removedPaths;
    private long serverTime;
    private boolean fullSnapshot;
    /** 全量分页/bootstrap 时是否还有下一页 */
    private boolean hasMore;
    /** 全量分页游标：下一页请求传 cursor=nextCursor */
    private String nextCursor;
}
