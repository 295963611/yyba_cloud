package com.yyba.cloud.util;

import android.content.Context;

import com.yyba.cloud.api.SyncIndexItem;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.db.CloudIndexEntity;

import java.util.ArrayList;
import java.util.List;

/** Room 持久化云端 sync-index，支持离线识别「仅云端」项 */
public final class CloudIndexStore {

    private CloudIndexStore() {
    }

    public static List<SyncIndexItem> loadAll(Context context) {
        List<CloudIndexEntity> entities = AppDatabase.getInstance(context).cloudIndexDao().findAll();
        List<SyncIndexItem> items = new ArrayList<>(entities.size());
        for (CloudIndexEntity entity : entities) {
            items.add(toItem(entity));
        }
        return items;
    }

    public static int count(Context context) {
        return AppDatabase.getInstance(context).cloudIndexDao().count();
    }

    public static long maxUpdatedAt(Context context) {
        Long max = AppDatabase.getInstance(context).cloudIndexDao().maxUpdatedAt();
        return max != null ? max : 0L;
    }

    public static void replaceAll(Context context, List<SyncIndexItem> items) {
        AppDatabase db = AppDatabase.getInstance(context);
        db.runInTransaction(() -> {
            db.cloudIndexDao().deleteAll();
            if (items != null && !items.isEmpty()) {
                db.cloudIndexDao().upsertAll(toEntities(items));
            }
        });
    }

    public static void merge(Context context, List<SyncIndexItem> items, List<String> removedPaths) {
        AppDatabase db = AppDatabase.getInstance(context);
        db.runInTransaction(() -> {
            if (removedPaths != null && !removedPaths.isEmpty()) {
                db.cloudIndexDao().deleteByPaths(removedPaths);
            }
            if (items != null && !items.isEmpty()) {
                db.cloudIndexDao().upsertAll(toEntities(items));
            }
        });
    }

    public static void clear(Context context) {
        AppDatabase.getInstance(context).cloudIndexDao().deleteAll();
    }

    private static List<CloudIndexEntity> toEntities(List<SyncIndexItem> items) {
        List<CloudIndexEntity> entities = new ArrayList<>(items.size());
        for (SyncIndexItem item : items) {
            if (item == null || item.relativePath == null) {
                continue;
            }
            CloudIndexEntity entity = new CloudIndexEntity();
            entity.relativePath = item.relativePath;
            entity.sha256 = item.sha256;
            entity.fileSize = item.fileSize;
            entity.fileType = item.fileType;
            entity.thumbnailPath = item.thumbnailPath;
            entity.share = item.share;
            entity.capturedAt = item.capturedAt;
            entity.updatedAt = item.updatedAt != null ? item.updatedAt : 0L;
            entities.add(entity);
        }
        return entities;
    }

    private static SyncIndexItem toItem(CloudIndexEntity entity) {
        SyncIndexItem item = new SyncIndexItem();
        item.relativePath = entity.relativePath;
        item.sha256 = entity.sha256;
        item.fileSize = entity.fileSize;
        item.fileType = entity.fileType;
        item.thumbnailPath = entity.thumbnailPath;
        item.share = entity.share;
        item.capturedAt = entity.capturedAt;
        item.updatedAt = entity.updatedAt;
        return item;
    }
}
