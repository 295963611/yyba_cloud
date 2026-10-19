package com.yyba.cloud.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.RawQuery;
import androidx.sqlite.db.SupportSQLiteQuery;

import java.util.List;

@Dao
public interface SyncRecordDao {

    @Query("SELECT * FROM sync_records WHERE localUri = :uri AND fileHash = :hash LIMIT 1")
    SyncRecord findByUriAndHash(String uri, String hash);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SyncRecord record);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<SyncRecord> records);

    @Query("SELECT COUNT(*) FROM sync_records")
    int count();

    @Query("SELECT * FROM sync_records WHERE localUri = :uri LIMIT 1")
    SyncRecord findByLocalUri(String uri);

    @Query("SELECT * FROM sync_records WHERE remotePath = :remotePath LIMIT 1")
    SyncRecord findByRemotePath(String remotePath);

    @Query("SELECT * FROM sync_records WHERE fileHash = :hash")
    List<SyncRecord> findByFileHash(String hash);

    @Query("DELETE FROM sync_records WHERE remotePath = :remotePath")
    void deleteByRemotePath(String remotePath);

    @Query("DELETE FROM sync_records WHERE localUri = :localUri")
    void deleteByLocalUri(String localUri);

    @Query("SELECT * FROM sync_records WHERE remoteFileId IS NOT NULL AND remoteFileId > 0")
    List<SyncRecord> findAllWithRemoteFileId();

    @Query("SELECT * FROM sync_records WHERE localUri IN (:uris)")
    List<SyncRecord> findByLocalUris(List<String> uris);

    @Query("SELECT * FROM sync_records WHERE remotePath IN (:paths)")
    List<SyncRecord> findByRemotePaths(List<String> paths);

    @Query("SELECT * FROM sync_records WHERE remoteFileId IN (:fileIds)")
    List<SyncRecord> findByRemoteFileIds(List<Long> fileIds);

    @Query("SELECT * FROM sync_records WHERE remoteFileId IS NOT NULL AND remoteFileId > 0 "
            + "AND fileName = :fileName AND fileSize = :fileSize LIMIT 1")
    SyncRecord findSyncedByNameAndSize(String fileName, long fileSize);

    @Query("SELECT * FROM sync_records WHERE remoteFileId IS NOT NULL AND remoteFileId > 0 "
            + "AND mediaType = :mediaType AND localUri IS NOT NULL AND localUri != ''")
    List<SyncRecord> findSyncedWithLocalUriByMediaType(String mediaType);

    @Query("UPDATE sync_records SET remoteFileId = NULL, remotePath = NULL WHERE localUri = :localUri")
    void clearRemoteByLocalUri(String localUri);

    @Query("UPDATE sync_records SET remoteFileId = NULL, remotePath = NULL WHERE remoteFileId = :fileId")
    void clearRemoteByFileId(long fileId);

    @Query("DELETE FROM sync_records WHERE localUri = :localUri "
            + "AND (remoteFileId IS NULL OR remoteFileId <= 0)")
    void deleteIncompleteByLocalUri(String localUri);

    @Query("SELECT * FROM sync_records WHERE fileHash IS NOT NULL AND fileHash != '' "
            + "AND (remoteFileId IS NULL OR remoteFileId <= 0) LIMIT :limit")
    List<SyncRecord> findIncompleteWithHash(int limit);

    @Query("SELECT * FROM sync_records")
    List<SyncRecord> findAll();

    @Query("DELETE FROM sync_records")
    void deleteAll();

    /** 清理旧格式 localUri 脏数据：file:/// 前缀和纯 /storage/ 路径（非 content:// 的手机备份记录） */
    @Query("DELETE FROM sync_records WHERE localUri LIKE 'file://%' "
            + "OR (localUri NOT LIKE 'content://%' AND localUri LIKE '/%')")
    int deleteLegacyFileUris();

    /**
     * 批量按 (fileName, fileSize) 查询已同步记录（remoteFileId > 0）。
     * Room 原生不支持多列 tuple IN，用 RawQuery 动态构建 OR 链。
     * SQLite 默认参数上限 999，每对占 2 参数，目录文件数 <400 安全。
     */
    @RawQuery
    List<SyncRecord> findSyncedByNamesAndSizes(SupportSQLiteQuery query);
}
