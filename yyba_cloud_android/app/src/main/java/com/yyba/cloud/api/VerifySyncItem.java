package com.yyba.cloud.api;

import com.google.gson.JsonObject;

public class VerifySyncItem {
    public Long remoteFileId;
    public String localUri;
    public String relativePath;
    public Long fileSize;
    public Long capturedAt;
    public String fileName;
    public String mediaType;
    public Long mediaId;
    public String fileHash;

    public JsonObject toJsonObject() {
        JsonObject obj = new JsonObject();
        if (remoteFileId != null && remoteFileId > 0) {
            obj.addProperty("remoteFileId", remoteFileId);
            return obj;
        }
        if (localUri != null) {
            obj.addProperty("localUri", localUri);
        }
        if (relativePath != null) {
            obj.addProperty("relativePath", relativePath);
        }
        if (fileSize != null && fileSize > 0) {
            obj.addProperty("fileSize", fileSize);
        }
        if (capturedAt != null && capturedAt > 0) {
            obj.addProperty("capturedAt", capturedAt);
        }
        if (fileName != null) {
            obj.addProperty("fileName", fileName);
        }
        if (mediaType != null) {
            obj.addProperty("mediaType", mediaType);
        }
        if (mediaId != null && mediaId > 0) {
            obj.addProperty("mediaId", mediaId);
        }
        if (fileHash != null && !fileHash.isEmpty()) {
            obj.addProperty("fileHash", fileHash);
        }
        return obj;
    }
}
