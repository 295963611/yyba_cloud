package com.yyba.cloud.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface CloudIndexDao {

    @Query("SELECT * FROM cloud_index")
    List<CloudIndexEntity> findAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<CloudIndexEntity> items);

    @Query("DELETE FROM cloud_index WHERE relativePath IN (:paths)")
    void deleteByPaths(List<String>paths);

    @Query("SELECT MAX(updatedAt) FROM cloud_index")
    Long maxUpdatedAt();

    @Query("SELECT COUNT(*) FROM cloud_index")
    int count();

    @Query("DELETE FROM cloud_index")
    void deleteAll();
}
