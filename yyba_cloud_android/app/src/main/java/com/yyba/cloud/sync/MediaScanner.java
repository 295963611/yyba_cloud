package com.yyba.cloud.sync;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.yyba.cloud.db.SyncRecord;
import com.yyba.cloud.util.DocumentTypes;
import com.yyba.cloud.util.HashUtils;
import com.yyba.cloud.util.SyncStatusHelper;

import java.io.InputStream;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MediaScanner {

    private static final DateTimeFormatter MONTH_PATH_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy/MM", Locale.getDefault())
                    .withZone(ZoneId.systemDefault());

    public static final int DEFAULT_PAGE_SIZE = 50;

    public static class MediaItem {
        public Uri uri;
        public long mediaId;
        public String fileName;
        public String mediaType;
        public long modifiedAt;
        public long takenAt;
        public long fileSize;
        public String relativePath;
        /** 云端索引匹配到的真实路径，用于回填 sync_records */
        public String serverMatchedPath;
    }

    public static class MediaPage {
        public final List<MediaItem> items;
        public final boolean hasMore;

        public MediaPage(List<MediaItem> items, boolean hasMore) {
            this.items = items;
            this.hasMore = hasMore;
        }
    }

    public List<MediaItem> scanAll(Context context) {
        List<MediaItem> items = new ArrayList<>();
        items.addAll(scanImages(context));
        items.addAll(scanVideos(context));
        items.addAll(scanAudio(context));
        return items;
    }

    public MediaPage scanPage(Context context, String category, int offset, int limit) {
        Uri collection = collectionForCategory(category);
        if (collection == null) {
            return new MediaPage(new ArrayList<>(), false);
        }
        String selection = null;
        String[] selectionArgs = null;
        if ("AUDIO".equals(category)) {
            AudioQuery audioQuery = audioQuery();
            collection = audioQuery.collection;
            selection = audioQuery.selection;
            selectionArgs = audioQuery.selectionArgs;
        } else if ("DOCUMENT".equals(category)) {
            DocumentQuery documentQuery = documentQuery();
            collection = documentQuery.collection;
            selection = documentQuery.selection;
            selectionArgs = documentQuery.selectionArgs;
        } else if ("OTHER".equals(category) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = MediaStore.Files.getContentUri("external");
            selection = MediaStore.Files.FileColumns.MEDIA_TYPE + "=?";
            selectionArgs = new String[]{String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)};
        }
        List<MediaItem> items = scanWithPaging(context, collection, category, selection, selectionArgs, offset, limit);
        return new MediaPage(items, items.size() >= limit);
    }

    public List<MediaItem> scanImages(Context context) {
        return scan(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "IMAGE");
    }

    /** 按 MediaStore URI 查询单条媒体，供下载绑定后就地插入列表 */
    @Nullable
    public MediaItem queryByUri(Context context, Uri uri, String mediaType) {
        if (context == null || uri == null || mediaType == null) {
            return null;
        }
        Uri collection = collectionForCategory(mediaType);
        if (collection == null) {
            return null;
        }
        boolean imageType = "IMAGE".equals(mediaType);
        boolean videoType = "VIDEO".equals(mediaType);
        boolean useTaken = imageType || videoType;
        String[] projection = useTaken
                ? new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                imageType ? MediaStore.Images.Media.DATE_TAKEN : MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.MediaColumns.SIZE
        }
                : new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE
        };
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int takenCol = useTaken
                    ? cursor.getColumnIndexOrThrow(imageType
                    ? MediaStore.Images.Media.DATE_TAKEN
                    : MediaStore.Video.Media.DATE_TAKEN)
                    : -1;
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);

            long id = cursor.getLong(idCol);
            String name = cursor.getString(nameCol);
            if (name == null || name.isEmpty()) {
                return null;
            }
            long modified = cursor.getLong(modCol) * 1000L;
            long taken = useTaken && takenCol >= 0 ? cursor.getLong(takenCol) : 0L;
            if (taken <= 0) {
                taken = modified;
            }
            long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : 0;

            MediaItem item = new MediaItem();
            item.uri = ContentUris.withAppendedId(collection, id);
            item.mediaId = id;
            item.fileName = name;
            item.mediaType = mediaType;
            item.modifiedAt = modified;
            item.takenAt = taken;
            item.fileSize = size;
            item.relativePath = buildRemotePath(mediaType, name, modified);
            return item;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 按文件大小筛选后比对哈希，用于云端下载时关联已有本地照片/视频，避免重复落盘。
     */
    @Nullable
    public MediaItem findByContentHash(Context context, String category, String hash, long fileSize) {
        if (context == null || hash == null || hash.isEmpty() || fileSize <= 0) {
            return null;
        }
        Uri collection = collectionForCategory(category);
        if (collection == null) {
            return null;
        }
        boolean imageType = "IMAGE".equals(category);
        boolean videoType = "VIDEO".equals(category);
        boolean useTaken = imageType || videoType;
        String[] projection = useTaken
                ? new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                imageType ? MediaStore.Images.Media.DATE_TAKEN : MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.MediaColumns.SIZE
        }
                : new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE
        };
        String selection = MediaStore.MediaColumns.SIZE + "=?";
        String[] selectionArgs = new String[]{String.valueOf(fileSize)};
        int checked = 0;
        final int maxCandidates = 32;
        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, selection, selectionArgs,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) {
                return null;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int takenCol = useTaken
                    ? cursor.getColumnIndexOrThrow(imageType
                    ? MediaStore.Images.Media.DATE_TAKEN
                    : MediaStore.Video.Media.DATE_TAKEN)
                    : -1;
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            while (cursor.moveToNext() && checked < maxCandidates) {
                checked++;
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                Uri uri = ContentUris.withAppendedId(collection, id);
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        continue;
                    }
                    if (!hash.equals(HashUtils.sha256(in))) {
                        continue;
                    }
                } catch (Exception ignored) {
                    continue;
                }
                long modified = cursor.getLong(modCol) * 1000L;
                long taken = useTaken && takenCol >= 0 ? cursor.getLong(takenCol) : 0L;
                if (taken <= 0) {
                    taken = modified;
                }
                long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : 0;
                MediaItem item = new MediaItem();
                item.uri = uri;
                item.mediaId = id;
                item.fileName = name;
                item.mediaType = category;
                item.modifiedAt = modified;
                item.takenAt = taken;
                item.fileSize = size;
                item.relativePath = buildRemotePath(category, name, modified);
                return item;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 收集本地全部图片的云盘相对路径，用于相册与服务端去重。 */
    public Set<String> collectImageRelativePaths(Context context) {
        return collectMediaRelativePaths(context, "IMAGE");
    }

    /** 收集本地全部视频的云盘相对路径。 */
    public Set<String> collectVideoRelativePaths(Context context) {
        return collectMediaRelativePaths(context, "VIDEO");
    }

    public Set<String> collectMediaRelativePaths(Context context, String category) {
        Set<String> paths = new HashSet<>();
        Uri collection = collectionForCategory(category);
        String selection = null;
        String[] selectionArgs = null;
        if ("AUDIO".equals(category)) {
            AudioQuery audioQuery = audioQuery();
            collection = audioQuery.collection;
            selection = audioQuery.selection;
            selectionArgs = audioQuery.selectionArgs;
        }
        if (collection == null) {
            return paths;
        }
        String[] projection = {
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, selection, selectionArgs,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) {
                return paths;
            }
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameCol);
                long modified = cursor.getLong(modCol) * 1000L;
                paths.add(buildRemotePath(category, name, modified));
            }
        }
        return paths;
    }

    public List<MediaItem> scanVideos(Context context) {
        return scan(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "VIDEO");
    }

    public List<MediaItem> scanAudio(Context context) {
        AudioQuery audioQuery = audioQuery();
        return scanWithPaging(context, audioQuery.collection, "AUDIO",
                audioQuery.selection, audioQuery.selectionArgs, 0, Integer.MAX_VALUE);
    }

    /**
     * Android 10+ 通过 Files 库扫描全部音频与常见录音目录；
     * 旧版本仍使用 Audio.Media。
     */
    private static AudioQuery audioQuery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String selection = "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?)"
                    + " OR (" + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE ?)"
                    + " OR (" + MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?)"
                    + " OR (" + MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?)"
                    + " OR (" + MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ?)"
                    + " OR (" + MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?)"
                    + " OR (" + MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?)"
                    + " OR (" + MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?)"
                    + " OR (" + MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?)"
                    + " OR (" + MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?)";
            String[] selectionArgs = new String[]{
                    String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO),
                    "audio/%",
                    "%Record%",
                    "%record%",
                    "%Sound_recorder%",
                    "%.m4a",
                    "%.amr",
                    "%.3gp",
                    "%.aac",
                    "%.mp3"
            };
            return new AudioQuery(MediaStore.Files.getContentUri("external"), selection, selectionArgs);
        }
        return new AudioQuery(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null, null);
    }

    /** 按扩展名扫描 Office/PDF/TXT 文档（含 xlsx/pptx 等非 MEDIA_TYPE_DOCUMENT 项） */
    private static DocumentQuery documentQuery() {
        String nameCol = MediaStore.MediaColumns.DISPLAY_NAME;
        StringBuilder selection = new StringBuilder();
        String[] selectionArgs = new String[DocumentTypes.EXTENSIONS.size()];
        for (int i = 0; i < DocumentTypes.EXTENSIONS.size(); i++) {
            if (i > 0) {
                selection.append(" OR ");
            }
            selection.append("LOWER(").append(nameCol).append(") LIKE ?");
            selectionArgs[i] = "%." + DocumentTypes.EXTENSIONS.get(i);
        }
        return new DocumentQuery(MediaStore.Files.getContentUri("external"),
                selection.toString(), selectionArgs);
    }

    private static final class DocumentQuery {
        final Uri collection;
        final String selection;
        final String[] selectionArgs;

        DocumentQuery(Uri collection, String selection, String[] selectionArgs) {
            this.collection = collection;
            this.selection = selection;
            this.selectionArgs = selectionArgs;
        }
    }

    private static final class AudioQuery {
        final Uri collection;
        final String selection;
        final String[] selectionArgs;

        AudioQuery(Uri collection, String selection, String[] selectionArgs) {
            this.collection = collection;
            this.selection = selection;
            this.selectionArgs = selectionArgs;
        }
    }

    public List<MediaItem> scanByCategory(Context context, String category) {
        switch (category) {
            case "IMAGE": return scanImages(context);
            case "VIDEO": return scanVideos(context);
            case "AUDIO": return scanAudio(context);
            case "DOCUMENT":
            case "OTHER": {
                MediaPage page = scanPage(context, category, 0, Integer.MAX_VALUE);
                return page.items;
            }
            default: return new ArrayList<>();
        }
    }

    private Uri collectionForCategory(String category) {
        switch (category) {
            case "IMAGE": return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            case "VIDEO": return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            case "AUDIO": return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            case "DOCUMENT":
            case "OTHER": return MediaStore.Files.getContentUri("external");
            default: return null;
        }
    }

    private List<MediaItem> scan(Context context, Uri collection, String type) {
        return scanWithPaging(context, collection, type, null, null, 0, Integer.MAX_VALUE);
    }

    private List<MediaItem> scanWithPaging(Context context, Uri collection, String type,
                                           String selection, String[] selectionArgs,
                                           int offset, int limit) {
        List<MediaItem> items = new ArrayList<>();
        boolean imageType = "IMAGE".equals(type);
        boolean videoType = "VIDEO".equals(type);
        boolean useTaken = imageType || videoType;
        String sortColumn = useTaken
                ? (imageType ? MediaStore.Images.Media.DATE_TAKEN : MediaStore.Video.Media.DATE_TAKEN)
                : MediaStore.MediaColumns.DATE_MODIFIED;
        String[] projection = useTaken
                ? new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                imageType ? MediaStore.Images.Media.DATE_TAKEN : MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.MediaColumns.SIZE
        }
                : new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE
        };

        Bundle queryArgs = new Bundle();
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,
                new String[]{sortColumn});
        queryArgs.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        queryArgs.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
        queryArgs.putInt(ContentResolver.QUERY_ARG_OFFSET, offset);
        if (selection != null) {
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
            queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
        }

        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, queryArgs, null)) {
            if (cursor == null) return items;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int takenCol = useTaken
                    ? cursor.getColumnIndexOrThrow(imageType
                    ? MediaStore.Images.Media.DATE_TAKEN
                    : MediaStore.Video.Media.DATE_TAKEN)
                    : -1;
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                if (name == null || name.isEmpty()) continue;
                if ("DOCUMENT".equals(type) && !DocumentTypes.isDocumentFileName(name)) {
                    continue;
                }
                long modified = cursor.getLong(modCol) * 1000L;
                long taken = useTaken && takenCol >= 0 ? cursor.getLong(takenCol) : 0L;
                if (taken <= 0) {
                    taken = modified;
                }
                long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : 0;
                Uri uri = ContentUris.withAppendedId(collection, id);

                MediaItem item = new MediaItem();
                item.uri = uri;
                item.mediaId = id;
                item.fileName = name;
                item.mediaType = type;
                item.modifiedAt = modified;
                item.takenAt = taken;
                item.fileSize = size;
                item.relativePath = buildRemotePath(type, name, modified);
                items.add(item);
            }
        }
        return items;
    }

    /** 首页统计：MediaStore 集合总数（相片/视频分开） */
    public int countMediaStoreTotal(Context context, String category) {
        Uri collection = collectionForCategory(category);
        if (collection == null) {
            return 0;
        }
        String selection = null;
        String[] selectionArgs = null;
        if ("AUDIO".equals(category)) {
            AudioQuery audioQuery = audioQuery();
            collection = audioQuery.collection;
            selection = audioQuery.selection;
            selectionArgs = audioQuery.selectionArgs;
        }
        Bundle queryArgs = new Bundle();
        if (selection != null) {
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
            queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
        }
        try (Cursor cursor = context.getContentResolver().query(
                collection, new String[]{MediaStore.MediaColumns._ID}, queryArgs, null)) {
            return cursor != null ? cursor.getCount() : 0;
        }
    }

    /** 首页统计：游标遍历，不构建全量 MediaItem 列表 */
    public int countUnsynced(Context context, String category,
                             Map<String, SyncRecord> localIndex) {
        return collectUnsynced(context, category, localIndex).size();
    }

    /** 收集某类媒体中尚未同步的项，供立即同步与统计共用口径 */
    public List<MediaItem> collectUnsynced(Context context, String category,
                                           Map<String, SyncRecord> localIndex) {
        Uri collection = collectionForCategory(category);
        if (collection == null) {
            return new ArrayList<>();
        }
        String selection = null;
        String[] selectionArgs = null;
        if ("AUDIO".equals(category)) {
            AudioQuery audioQuery = audioQuery();
            collection = audioQuery.collection;
            selection = audioQuery.selection;
            selectionArgs = audioQuery.selectionArgs;
        }
        return collectUnsyncedInCollection(context, collection, category, selection, selectionArgs,
                localIndex);
    }

    /** @deprecated 使用 {@link #countUnsynced(Context, String, Map)} */
    @Deprecated
    public int countUnsynced(Context context, String category,
                             Map<String, SyncRecord> localIndex, Set<String> serverPaths) {
        return countUnsynced(context, category, localIndex);
    }

    private List<MediaItem> collectUnsyncedInCollection(Context context, Uri collection, String type,
                                                        String selection, String[] selectionArgs,
                                                        Map<String, SyncRecord> localIndex) {
        List<MediaItem> items = new ArrayList<>();
        boolean imageType = "IMAGE".equals(type);
        boolean videoType = "VIDEO".equals(type);
        boolean useTaken = imageType || videoType;
        String sortColumn = useTaken
                ? (imageType ? MediaStore.Images.Media.DATE_TAKEN : MediaStore.Video.Media.DATE_TAKEN)
                : MediaStore.MediaColumns.DATE_MODIFIED;
        String[] projection = useTaken
                ? new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                imageType ? MediaStore.Images.Media.DATE_TAKEN : MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.MediaColumns.SIZE
        }
                : new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE
        };

        Bundle queryArgs = new Bundle();
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,
                new String[]{sortColumn});
        queryArgs.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        if (selection != null) {
            queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
            queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs);
        }

        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, queryArgs, null)) {
            if (cursor == null) {
                return items;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int takenCol = useTaken
                    ? cursor.getColumnIndexOrThrow(imageType
                    ? MediaStore.Images.Media.DATE_TAKEN
                    : MediaStore.Video.Media.DATE_TAKEN)
                    : -1;
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                long modified = cursor.getLong(modCol) * 1000L;
                long taken = useTaken && takenCol >= 0 ? cursor.getLong(takenCol) : 0L;
                if (taken <= 0) {
                    taken = modified;
                }
                long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : 0;
                Uri uri = ContentUris.withAppendedId(collection, id);

                MediaItem item = new MediaItem();
                item.uri = uri;
                item.mediaId = id;
                item.fileName = name;
                item.mediaType = type;
                item.modifiedAt = modified;
                item.takenAt = taken;
                item.fileSize = size;
                item.relativePath = buildRemotePath(type, name, modified);
                SyncRecord record = localIndex != null ? localIndex.get(item.uri.toString()) : null;
                if (!SyncStatusHelper.isSyncedByRecord(record)) {
                    items.add(item);
                }
            }
        }
        return items;
    }

    public static String buildRemotePath(String type, String fileName, long modifiedAt) {
        String datePath = MONTH_PATH_FORMATTER.format(Instant.ofEpochMilli(modifiedAt));
        String folder;
        switch (type) {
            case "IMAGE": folder = "photos"; break;
            case "VIDEO": folder = "videos"; break;
            case "AUDIO": folder = "audio"; break;
            case "DOCUMENT": folder = "uploads/documents"; break;
            default: folder = "uploads/other"; break;
        }
        if (folder.startsWith("uploads/")) {
            return folder + "/" + datePath + "/" + fileName;
        }
        return folder + "/" + datePath + "/" + fileName;
    }
}
