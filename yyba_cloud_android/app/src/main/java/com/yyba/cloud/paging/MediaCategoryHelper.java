package com.yyba.cloud.paging;

import com.yyba.cloud.api.AlbumPhotoItem;
import com.yyba.cloud.api.SyncIndexItem;
import com.yyba.cloud.ui.MediaListAdapter;

import java.util.List;

public final class MediaCategoryHelper {

    private MediaCategoryHelper() {
    }

    public static String fileTypeForApi(String category) {
        if ("VIDEO".equals(category)) {
            return "VIDEO";
        }
        if ("AUDIO".equals(category)) {
            return "AUDIO";
        }
        if ("DOCUMENT".equals(category)) {
            return "DOCUMENT";
        }
        return "PHOTO";
    }

    public static boolean matchesCategoryPath(String category, String path) {
        if (path == null) {
            return false;
        }
        switch (category) {
            case "VIDEO":
                return path.startsWith("videos/");
            case "AUDIO":
                return path.startsWith("audio/") || path.startsWith("uploads/audio/");
            case "DOCUMENT":
                return path.startsWith("uploads/documents/");
            default:
                return path.startsWith("photos/");
        }
    }

    public static String pathPrefixForCategory(String category) {
        switch (category) {
            case "IMAGE":
                return "photos/";
            case "VIDEO":
                return "videos/";
            case "AUDIO":
                return "audio/";
            case "DOCUMENT":
                return "uploads/documents/";
            case "OTHER":
                return "uploads/other/";
            default:
                return null;
        }
    }

    public static boolean matchesSyncIndexCategory(String category, String path, String prefix) {
        if ("AUDIO".equals(category) || "VIDEO".equals(category) || "IMAGE".equals(category)) {
            return matchesCategoryPath(category, path);
        }
        return path != null && prefix != null && path.startsWith(prefix);
    }

    public static boolean sortByCapturedTime(String tabType, String category) {
        return MediaListAdapter.TAB_PHONE.equals(tabType)
                && ("VIDEO".equals(category) || "IMAGE".equals(category));
    }

    public static void sortRowsByCapturedTime(List<MediaListAdapter.MediaRow> rows) {
        rows.sort((a, b) -> Long.compare(capturedMs(b), capturedMs(a)));
    }

    public static long capturedMs(SyncIndexItem item) {
        if (item == null || item.capturedAt == null || item.capturedAt <= 0L) {
            return 0L;
        }
        return item.capturedAt;
    }

    public static long capturedMs(MediaListAdapter.MediaRow row) {
        if (row.item != null) {
            long taken = row.item.takenAt;
            if (taken > 0L) {
                return taken;
            }
            return row.item.modifiedAt > 0L ? row.item.modifiedAt : 0L;
        }
        if (row.cloudItem != null && row.cloudItem.capturedAt != null && row.cloudItem.capturedAt > 0L) {
            return row.cloudItem.capturedAt;
        }
        return 0L;
    }

    public static AlbumPhotoItem toAlbumPhotoItem(SyncIndexItem item) {
        AlbumPhotoItem photo = new AlbumPhotoItem();
        photo.relativePath = item.relativePath;
        photo.sha256 = item.sha256;
        photo.fileSize = item.fileSize;
        photo.thumbnailPath = item.thumbnailPath;
        photo.share = item.share;
        photo.capturedAt = item.capturedAt;
        return photo;
    }
}
