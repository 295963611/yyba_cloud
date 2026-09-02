package com.yyba.cloud.upload;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.yyba.cloud.api.ApiClient;
import com.yyba.cloud.db.AppDatabase;
import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.db.UploadTask;
import com.yyba.cloud.util.HashUtils;
import com.yyba.cloud.util.PrefsManager;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ChunkUploader {

    private final Context context;
    private final ApiClient apiClient;
    private final AppDatabase db;
    private final Gson gson = new Gson();
    private static final int DEFAULT_CHUNK_SIZE = 20 * 1024 * 1024;

    public ChunkUploader(Context context) {
        this.context = context.getApplicationContext();
        PrefsManager prefs = new PrefsManager(this.context);
        this.apiClient = new ApiClient(prefs);
        this.db = AppDatabase.getInstance(this.context);
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public long uploadMedia(Uri uri, String fileName, String mediaType, long modifiedAt,
                            long takenAt, long mediaId, String relativePath) throws Exception {
        return uploadMedia(uri, fileName, mediaType, modifiedAt, takenAt, mediaId, relativePath, null);
    }

    public long uploadMedia(Uri uri, String fileName, String mediaType, long modifiedAt,
                            long takenAt, long mediaId, String relativePath,
                            ProgressListener progressListener) throws Exception {
        reportProgress(progressListener, 1);
        long size = getSize(uri);
        if (size <= 0) {
            throw new Exception("无法读取文件大小");
        }
        String hash = computeHash(uri);
        reportProgress(progressListener, 8);

        SyncRecord existing = db.syncRecordDao().findByUriAndHash(uri.toString(), hash);
        if (existing != null && existing.modifiedAt == modifiedAt) {
            reportProgress(progressListener, 100);
            return -1;
        }

        UploadTask task = db.uploadTaskDao().findByUri(uri.toString());
        if (task == null) {
            task = new UploadTask();
            task.localUri = uri.toString();
            task.fileName = fileName;
            task.relativePath = relativePath;
            task.fileSize = size;
            task.fileHash = hash;
            task.mediaType = mediaType;
            task.status = "PENDING";
            task.createdAt = System.currentTimeMillis();
            task.updatedAt = task.createdAt;
            db.uploadTaskDao().insert(task);
            task = db.uploadTaskDao().findByUri(uri.toString());
        } else {
            task.fileName = fileName;
            task.relativePath = relativePath;
            task.fileSize = size;
            task.fileHash = hash;
            task.mediaType = mediaType;
            task.updatedAt = System.currentTimeMillis();
            db.uploadTaskDao().update(task);
        }

        try {
            return doUpload(uri, fileName, mediaType, modifiedAt, takenAt, mediaId, relativePath,
                    hash, size, task, progressListener);
        } catch (DuplicateSkipException e) {
            reportProgress(progressListener, 100);
            return -1;
        } catch (Exception e) {
            if (isSessionError(e)) {
                resetUploadSession(task);
                return doUpload(uri, fileName, mediaType, modifiedAt, takenAt, mediaId, relativePath,
                        hash, size, task, progressListener);
            }
            throw e;
        }
    }

    private long doUpload(Uri uri, String fileName, String mediaType, long modifiedAt,
                          long takenAt, long mediaId, String relativePath, String hash, long size,
                          UploadTask task, ProgressListener progressListener) throws Exception {
        String mimeType = guessMimeType(fileName, mediaType);
        ApiClient.UploadInitResult init = resolveUploadInit(task, fileName, relativePath, size,
                mimeType, hash, takenAt, modifiedAt, mediaId);
        reportProgress(progressListener, 12);

        int chunkSize = init.chunkSize > 0 ? init.chunkSize : DEFAULT_CHUNK_SIZE;
        Set<Integer> uploaded = new HashSet<>();
        if (init.uploadedChunks != null) uploaded.addAll(init.uploadedChunks);
        uploaded.addAll(readChunks(task.uploadedChunks));

        for (int i = 0; i < init.totalChunks; i++) {
            if (uploaded.contains(i)) continue;
            byte[] chunkData = readChunk(uri, i, chunkSize, size);
            apiClient.uploadChunk(init.uploadId, i, chunkData);
            uploaded.add(i);
            task.uploadedChunks = writeChunks(uploaded);
            task.updatedAt = System.currentTimeMillis();
            db.uploadTaskDao().update(task);
            int chunkPercent = init.totalChunks > 0
                    ? 12 + (int) (uploaded.size() * 83L / init.totalChunks)
                    : 95;
            reportProgress(progressListener, chunkPercent);
        }

        reportProgress(progressListener, 96);
        var result = apiClient.completeUpload(init.uploadId);
        task.status = "COMPLETED";
        task.updatedAt = System.currentTimeMillis();
        db.uploadTaskDao().update(task);

        SyncRecord record = new SyncRecord();
        record.mediaId = mediaId;
        record.localUri = uri.toString();
        record.fileName = fileName;
        record.fileSize = size;
        record.fileHash = hash;
        record.mediaType = mediaType;
        record.modifiedAt = modifiedAt;
        record.remotePath = relativePath;
        if (result.has("fileId")) {
            record.remoteFileId = result.get("fileId").getAsLong();
        }
        record.syncedAt = System.currentTimeMillis();
        db.syncRecordDao().insert(record);
        reportProgress(progressListener, 100);
        return record.remoteFileId != null ? record.remoteFileId : 0;
    }

    private ApiClient.UploadInitResult resolveUploadInit(UploadTask task, String fileName,
                                                           String relativePath, long size,
                                                           String mimeType, String hash,
                                                           long takenAt, long modifiedAt,
                                                           long mediaId) throws Exception {
        if (task.uploadId != null && !task.uploadId.isEmpty()) {
            try {
                ApiClient.UploadInitResult status = apiClient.getUploadStatus(task.uploadId);
                if (isSessionUsable(status)) {
                    status.uploadId = task.uploadId;
                    status.chunkSize = status.chunkSize > 0 ? status.chunkSize : DEFAULT_CHUNK_SIZE;
                    if (status.totalChunks <= 0) {
                        status.totalChunks = task.totalChunks > 0
                                ? task.totalChunks
                                : (int) Math.ceil((double) size / status.chunkSize);
                    }
                    return status;
                }
            } catch (Exception ignored) {
            }
            resetUploadSession(task);
        }

        try {
            Long capturedAt = takenAt > 0 ? takenAt : (modifiedAt > 0 ? modifiedAt : null);
            ApiClient.UploadInitResult init = apiClient.initUpload(fileName, relativePath, size,
                    mimeType, hash, capturedAt);
            task.uploadId = init.uploadId;
            task.totalChunks = init.totalChunks;
            task.status = "UPLOADING";
            task.updatedAt = System.currentTimeMillis();
            db.uploadTaskDao().update(task);
            return init;
        } catch (Exception e) {
            if (isDuplicateOnServer(e)) {
                saveSyncedRecord(Uri.parse(task.localUri), fileName, task.mediaType, modifiedAt,
                        mediaId, relativePath, hash, size, e.getMessage());
                throw new DuplicateSkipException();
            }
            throw e;
        }
    }

    private static boolean isSessionUsable(ApiClient.UploadInitResult status) {
        if (status == null || status.status == null || status.status.isEmpty()) {
            return true;
        }
        String normalized = status.status.toUpperCase();
        return "INIT".equals(normalized) || "UPLOADING".equals(normalized);
    }

    private void resetUploadSession(UploadTask task) {
        task.uploadId = null;
        task.uploadedChunks = "[]";
        task.totalChunks = 0;
        task.status = "PENDING";
        task.updatedAt = System.currentTimeMillis();
        db.uploadTaskDao().update(task);
    }

    private static boolean isSessionError(Exception e) {
        if (e instanceof DuplicateSkipException) {
            return false;
        }
        String msg = e.getMessage();
        return msg != null && (msg.contains("上传会话不存在")
                || msg.contains("上传会话已失效")
                || msg.contains("分片未上传完成"));
    }

    private static final class DuplicateSkipException extends Exception {
    }

    private void reportProgress(ProgressListener listener, int percent) {
        if (listener != null) {
            listener.onProgress(percent);
        }
    }

    private String computeHash(Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取文件");
            return HashUtils.sha256(in);
        }
    }

    private long getSize(Uri uri) throws Exception {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    long size = cursor.getLong(idx);
                    if (size > 0) return size;
                }
            }
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return 0;
            long total = 0;
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) total += read;
            return total;
        }
    }

    private byte[] readChunk(Uri uri, long start, int len) throws Exception {
        byte[] data = new byte[len];
        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd != null) {
            try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                skipFully(fis, start);
                readFully(fis, data);
                return data;
            } finally {
                pfd.close();
            }
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取文件");
            skipFully(in, start);
            readFully(in, data);
            return data;
        }
    }

    private byte[] readChunk(Uri uri, int index, int chunkSize, long totalSize) throws Exception {
        long start = (long) index * chunkSize;
        int len = (int) Math.min(chunkSize, totalSize - start);
        return readChunk(uri, start, len);
    }

    private void skipFully(InputStream in, long bytes) throws Exception {
        long skipped = 0;
        while (skipped < bytes) {
            long n = in.skip(bytes - skipped);
            if (n > 0) {
                skipped += n;
                continue;
            }
            int r = in.read();
            if (r < 0) {
                throw new Exception("无法定位文件偏移: " + skipped + "/" + bytes);
            }
            skipped++;
        }
    }

    private void readFully(InputStream in, byte[] data) throws Exception {
        int read = 0;
        while (read < data.length) {
            int r = in.read(data, read, data.length - read);
            if (r < 0) {
                throw new Exception("分片读取不完整: " + read + "/" + data.length);
            }
            read += r;
        }
    }

    private boolean isDuplicateOnServer(Exception e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("文件已存在");
    }

    private void saveSyncedRecord(Uri uri, String fileName, String mediaType, long modifiedAt,
                                  long mediaId, String relativePath, String hash, long size,
                                  String errorMsg) {
        String remotePath = relativePath;
        if (errorMsg != null && errorMsg.contains("文件已存在:")) {
            remotePath = errorMsg.substring(errorMsg.indexOf("文件已存在:") + "文件已存在:".length()).trim();
        }
        SyncRecord record = new SyncRecord();
        record.mediaId = mediaId;
        record.localUri = uri.toString();
        record.fileName = fileName;
        record.fileSize = size;
        record.fileHash = hash;
        record.mediaType = mediaType;
        record.modifiedAt = modifiedAt;
        record.remotePath = remotePath;
        record.syncedAt = System.currentTimeMillis();
        db.syncRecordDao().insert(record);
    }

    private Set<Integer> readChunks(String json) {
        Type type = new TypeToken<List<Integer>>(){}.getType();
        List<Integer> list = gson.fromJson(json, type);
        return list != null ? new HashSet<>(list) : new HashSet<>();
    }

    private String guessMimeType(String fileName, String mediaType) {
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".bmp")) return "image/bmp";
            if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
            if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".3gp")) return "video/mp4";
            if (lower.endsWith(".mp3")) return "audio/mpeg";
            if (lower.endsWith(".m4a")) return "audio/mp4";
            if (lower.endsWith(".wav")) return "audio/wav";
            if (lower.endsWith(".flac")) return "audio/flac";
            if (lower.endsWith(".pdf")) return "application/pdf";
        }
        if ("VIDEO".equals(mediaType)) return "video/mp4";
        if ("IMAGE".equals(mediaType)) return "image/jpeg";
        if ("AUDIO".equals(mediaType)) return "audio/mpeg";
        return "application/octet-stream";
    }

    private String writeChunks(Set<Integer> chunks) {
        List<Integer> list = new ArrayList<>(chunks);
        java.util.Collections.sort(list);
        return gson.toJson(list);
    }
}
