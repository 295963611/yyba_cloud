package com.yyba.cloud.util;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class DownloadHelper {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String GALLERY_ROOT = "悠悠爸云盘";

    private DownloadHelper() {
    }

    public static final class SavedMedia {
        public final Uri uri;
        public final long mediaId;
        public final String fileName;
        public final long fileSize;
        public final long modifiedAtMs;
        public final String mediaType;

        SavedMedia(Uri uri, long mediaId, String fileName, long fileSize,
                   long modifiedAtMs, String mediaType) {
            this.uri = uri;
            this.mediaId = mediaId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.modifiedAtMs = modifiedAtMs;
            this.mediaType = mediaType;
        }
    }

    public static void saveToDownloads(Context context, String fileName, byte[] data) throws Exception {
        if (data == null) {
            throw new IllegalStateException("下载内容为空");
        }
        saveToDownloads(context, fileName, out -> out.write(data));
    }

    public static void saveToDownloads(Context context, String fileName, StreamWriter writer) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, guessMimeType(fileName));
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("无法写入下载目录");
            }
            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    throw new IllegalStateException("无法打开输出流");
                }
                writer.writeTo(os);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建下载目录");
        }
        File out = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            writer.writeTo(fos);
        }
    }

    /** 将已下载临时文件写入系统相册/视频库，供云端下载绑定同步使用 */
    public static SavedMedia saveMediaFromFile(Context context, String cloudRelativePath,
                                               String fileName, File sourceFile) throws Exception {
        if (cloudRelativePath.startsWith("videos/")) {
            return saveVideoFromFile(context, cloudRelativePath, fileName, sourceFile);
        }
        return saveImageFromFile(context, cloudRelativePath, fileName, sourceFile);
    }

    private static SavedMedia saveImageFromFile(Context context, String cloudRelativePath,
                                                String fileName, File sourceFile) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, guessMimeType(fileName));
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    galleryRelativePath(Environment.DIRECTORY_PICTURES, cloudRelativePath));
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Uri uri = context.getContentResolver().insert(collection, values);
            if (uri == null) {
                throw new IllegalStateException("无法写入相册");
            }
            try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                 InputStream in = new FileInputStream(sourceFile)) {
                if (os == null) {
                    throw new IllegalStateException("无法打开输出流");
                }
                copyStream(in, os);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            long mediaId = ContentUris.parseId(uri);
            long[] meta = queryMediaMeta(context, uri);
            return new SavedMedia(uri, mediaId, fileName, meta[0], meta[1], "IMAGE");
        }
        File dest = legacyGalleryFile(Environment.DIRECTORY_PICTURES, cloudRelativePath, fileName);
        copyFile(sourceFile, dest);
        Uri uri = Uri.fromFile(dest);
        return new SavedMedia(uri, 0L, fileName, dest.length(), dest.lastModified(), "IMAGE");
    }

    private static SavedMedia saveVideoFromFile(Context context, String cloudRelativePath,
                                                String fileName, File sourceFile) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, guessMimeType(fileName));
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    galleryRelativePath(Environment.DIRECTORY_MOVIES, cloudRelativePath));
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            Uri uri = context.getContentResolver().insert(collection, values);
            if (uri == null) {
                throw new IllegalStateException("无法写入视频库");
            }
            try (OutputStream os = context.getContentResolver().openOutputStream(uri);
                 InputStream in = new FileInputStream(sourceFile)) {
                if (os == null) {
                    throw new IllegalStateException("无法打开输出流");
                }
                copyStream(in, os);
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            long mediaId = ContentUris.parseId(uri);
            long[] meta = queryMediaMeta(context, uri);
            return new SavedMedia(uri, mediaId, fileName, meta[0], meta[1], "VIDEO");
        }
        return saveVideoFromFileLegacy(cloudRelativePath, fileName, sourceFile);
    }

    private static SavedMedia saveVideoFromFileLegacy(String cloudRelativePath, String fileName,
                                                      File sourceFile) throws Exception {
        File dest = legacyGalleryFile(Environment.DIRECTORY_MOVIES, cloudRelativePath, fileName);
        copyFile(sourceFile, dest);
        Uri uri = Uri.fromFile(dest);
        return new SavedMedia(uri, 0L, fileName, dest.length(), dest.lastModified(), "VIDEO");
    }

    /** @return [fileSize, modifiedAtMs] */
    private static long[] queryMediaMeta(Context context, Uri uri) {
        long size = 0L;
        long modifiedMs = System.currentTimeMillis();
        try (android.database.Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                size = cursor.getLong(0);
                modifiedMs = cursor.getLong(1) * 1000L;
            }
        } catch (Exception ignored) {
        }
        return new long[]{size, modifiedMs};
    }

    private static String galleryRelativePath(String mediaDir, String cloudRelativePath) {
        return mediaDir + "/" + GALLERY_ROOT + "/" + cloudRelativePath;
    }

    private static File legacyGalleryFile(String mediaDir, String cloudRelativePath, String fileName) throws Exception {
        int slash = cloudRelativePath.lastIndexOf('/');
        String parent = slash >= 0 ? cloudRelativePath.substring(0, slash) : "";
        File dir = new File(Environment.getExternalStoragePublicDirectory(mediaDir),
                GALLERY_ROOT + (parent.isEmpty() ? "" : "/" + parent));
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建相册目录");
        }
        return new File(dir, fileName);
    }

    private static void copyFile(File source, File dest) throws Exception {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            copyStream(in, out);
        }
    }

    public static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    static String guessMimeType(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".3gp")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    @FunctionalInterface
    public interface StreamWriter {
        void writeTo(OutputStream out) throws Exception;
    }
}
