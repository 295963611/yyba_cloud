package com.yyba.cloud.backup;

import java.util.ArrayList;
import java.util.List;

/** 备份执行结果 */
public class BackupResult {

    /** 跳过原因：本机已有同步记录（已备份） */
    public static final String REASON_ALREADY_SYNCED = "已备份";
    /** 跳过原因：服务端已存在相同内容（fileHash 命中，云端路径可能不同） */
    public static final String REASON_DUPLICATE_CONTENT = "有相同的图片已经备份";

    /** 单项结果 */
    public static class ItemResult {
        public final String fileName;
        public final String reason;

        public ItemResult(String fileName, String reason) {
            this.fileName = fileName;
            this.reason = reason;
        }
    }

    public final List<ItemResult> successItems = new ArrayList<>();
    public final List<ItemResult> skippedItems = new ArrayList<>();
    public final List<ItemResult> failedItems = new ArrayList<>();
    public boolean cancelled;

    public int getSuccessCount() {
        return successItems.size();
    }

    public int getSkippedCount() {
        return skippedItems.size();
    }

    public int getFailedCount() {
        return failedItems.size();
    }
}
