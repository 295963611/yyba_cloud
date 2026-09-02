package com.yyba.cloud.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "cloud_index")
public class CloudIndexEntity {

    @NonNull
    @PrimaryKey
    public String relativePath;
    public String sha256;
    public Long fileSize;
    public String fileType;
    public String thumbnailPath;
    public Integer share;
    public Long capturedAt;
    public Long updatedAt;
}
