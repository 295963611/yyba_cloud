package com.yyba.cloud.util;

import android.content.Context;

import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.api.VerifySyncResponse;
import com.yyba.cloud.api.VerifySyncValidItem;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.db.SyncRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「清理已备份」：批量 verify-sync（50 条/批），仅删除云端仍存在的本地副本。
 */
public final class BackedUpCleanupHelper {

    public static final int BATCH_SIZE = 50;

    public static final class Plan {
        public final List<SyncRecord> deleteLocally = new ArrayList<>();
        public final List<SyncRecord> clearRemoteOnly = new ArrayList<>();
    }

    private BackedUpCleanupHelper() {
    }

    public static List<SyncRecord> loadCandidates(Context context, String category) {
        Context app = context.getApplicationContext();
        List<SyncRecord> all = AppDatabase.getInstance(app).syncRecordDao().findAllWithRemoteFileId();
        return filterByCategory(all, category);
    }

    public static List<SyncRecord> filterByCategory(List<SyncRecord> records, String category) {
        if (records == null) {
            return List.of();
        }
        if (category == null || category.isEmpty()) {
            return records;
        }
        List<SyncRecord> filtered = new ArrayList<>();
        for (SyncRecord record : records) {
            if (record == null) {
                continue;
            }
            if (record.mediaType == null || category.equals(record.mediaType)) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    public static Plan verifyAndPlan(ApiClient api, List<SyncRecord> candidates) throws Exception {
        Plan plan = new Plan();
        if (candidates == null || candidates.isEmpty()) {
            return plan;
        }
        for (int offset = 0; offset < candidates.size(); offset += BATCH_SIZE) {
            int end = Math.min(offset + BATCH_SIZE, candidates.size());
            List<SyncRecord> batch = candidates.subList(offset, end);
            List<Long> fileIds = new ArrayList<>();
            for (SyncRecord record : batch) {
                if (record.remoteFileId != null && record.remoteFileId > 0) {
                    fileIds.add(record.remoteFileId);
                }
            }
            if (fileIds.isEmpty()) {
                continue;
            }
            VerifySyncResponse response = api.verifySync(fileIds);
            Set<Long> validIds = new HashSet<>();
            if (response.validItems != null) {
                for (VerifySyncValidItem item : response.validItems) {
                    if (item.fileId != null) {
                        validIds.add(item.fileId);
                    }
                }
            }
            for (SyncRecord record : batch) {
                if (record.remoteFileId == null || record.remoteFileId <= 0) {
                    continue;
                }
                if (validIds.contains(record.remoteFileId)) {
                    plan.deleteLocally.add(record);
                } else {
                    plan.clearRemoteOnly.add(record);
                }
            }
        }
        return plan;
    }

    public static void applyClearRemoteOnly(Context context, List<SyncRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        var dao = AppDatabase.getInstance(context.getApplicationContext()).syncRecordDao();
        for (SyncRecord record : records) {
            if (record.localUri != null) {
                dao.clearRemoteByLocalUri(record.localUri);
            } else if (record.remoteFileId != null) {
                dao.clearRemoteByFileId(record.remoteFileId);
            }
        }
    }

    public static void applyDeleteLocalRecords(Context context, List<SyncRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        var dao = AppDatabase.getInstance(context.getApplicationContext()).syncRecordDao();
        for (SyncRecord record : records) {
            if (record.localUri != null) {
                dao.deleteByLocalUri(record.localUri);
            }
        }
    }
}
