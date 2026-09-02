package com.yyba.cloud.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "upload_tasks")
public class UploadTask {

    @PrimaryKey(autoGenerate = true)
    public long id;
    public String uploadId;
    public String localUri;
    public String fileName;
    public String relativePath;
    public long fileSize;
    public String fileHash;
    public String mediaType;
    public int totalChunks;
    public String uploadedChunks = "[]";
    public String status = "PENDING";
    public String errorMessage;
    public long createdAt;
    public long updatedAt;
}
