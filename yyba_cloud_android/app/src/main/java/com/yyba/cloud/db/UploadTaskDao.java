package com.yyba.cloud.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface UploadTaskDao {

    @Query("SELECT * FROM upload_tasks WHERE status != 'COMPLETED'")
    List<UploadTask> findPending();

    @Query("SELECT * FROM upload_tasks WHERE localUri = :uri AND status != 'COMPLETED' LIMIT 1")
    UploadTask findByUri(String uri);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(UploadTask task);

    @Update
    void update(UploadTask task);

    @Query("DELETE FROM upload_tasks WHERE status = 'COMPLETED'")
    void deleteCompleted();

    @Query("DELETE FROM upload_tasks")
    void deleteAll();
}
