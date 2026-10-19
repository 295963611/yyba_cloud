package com.yyba.cloud.backup;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;

import com.yyba.cloud.util.DocumentTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** MediaStore 浏览：按 RELATIVE_PATH 支持扁平分组 + 层级目录树两种模式 */
public class LocalFileBrowser {

    private static final String TAG = "LocalFileBrowser";

    /** 目录树节点：支持嵌套 */
    public static class DirectoryNode {
        public final String fullPath;
        public final String name;
        public int directFileCount;
        public int totalFileCount;
        public final List<DirectoryNode> children = new ArrayList<>();

        public DirectoryNode(String fullPath, String name, int directFileCount, int totalFileCount) {
            this.fullPath = fullPath;
            this.name = name;
            this.directFileCount = directFileCount;
            this.totalFileCount = totalFileCount;
        }

        public boolean isRoot() {
            return "/".equals(fullPath) || fullPath.isEmpty();
        }
    }

    /** 扁平分组项 */
    public static class FileGroup {
        public final String relativePath;
        public final String displayPath;
        public final int fileCount;

        public FileGroup(String relativePath, int fileCount) {
            this.relativePath = relativePath;
            this.displayPath = relativePath.replace("/", " / ");
            this.fileCount = fileCount;
        }
    }

    /** MediaStore 文件项 */
    public static class MediaFile {
        public final Uri uri;
        public final long mediaId;
        public final String fileName;
        public final String mediaType;
        public final long fileSize;
        public final long modifiedAt;
        public final long takenAt;
        public final String relativePath;

        public MediaFile(Uri uri, long mediaId, String fileName, String mediaType,
                         long fileSize, long modifiedAt, long takenAt, String relativePath) {
            this.uri = uri;
            this.mediaId = mediaId;
            this.fileName = fileName;
            this.mediaType = mediaType;
            this.fileSize = fileSize;
            this.modifiedAt = modifiedAt;
            this.takenAt = takenAt;
            this.relativePath = relativePath;
        }

        public String getDisplayName() {
            return fileName;
        }
    }

    // ========== 层级目录树 ==========

    /** 获取顶层目录节点列表（递归构建完整树，root 的 children 就是顶层） */
    public static List<DirectoryNode> listDirectoryTree(Context context) {
        // 第一步：收集所有 RELATIVE_PATH 及其文件数
        Map<String, Integer> pathFileCount = new HashMap<>();
        collectAllPathCounts(context, pathFileCount);

        // 第二步：构建目录树
        DirectoryNode root = buildDirectoryTree(pathFileCount);

        Log.d(TAG, "listDirectoryTree: root children=" + root.children.size()
                + " totalFiles=" + root.totalFileCount);
        for (DirectoryNode child : root.children) {
            Log.d(TAG, "  dir: '" + child.fullPath + "' total=" + child.totalFileCount
                    + " direct=" + child.directFileCount + " children=" + child.children.size());
        }

        return root.children;
    }

    /** 递归获取某目录下的直接子目录 + 直接文件（用于层级导航） */
    public static DirectoryChildren listDirectoryChildren(Context context, String dirPath) {
        Map<String, Integer> pathFileCount = new HashMap<>();
        collectAllPathCounts(context, pathFileCount);

        DirectoryNode root = buildDirectoryTree(pathFileCount);
        DirectoryNode target = findNode(root, dirPath);

        List<DirectoryNode> childDirs = new ArrayList<>();
        List<MediaFile> directFiles = new ArrayList<>();

        if (target != null) {
            childDirs.addAll(target.children);
            // 收集该目录下的直接文件
            directFiles.addAll(listFilesInExactPath(context, dirPath));
        }

        return new DirectoryChildren(childDirs, directFiles);
    }

    /** 目录子项：子目录列表 + 直接文件列表 */
    public static class DirectoryChildren {
        public final List<DirectoryNode> directories;
        public final List<MediaFile> files;

        public DirectoryChildren(List<DirectoryNode> directories, List<MediaFile> files) {
            this.directories = directories;
            this.files = files;
        }
    }

    // ========== 扁平分组 ==========

    /** 查询所有可备份文件，按 RELATIVE_PATH 扁平分组 */
    public static List<FileGroup> listGroups(Context context) {
        Map<String, Integer> countMap = new HashMap<>();
        collectGroupCounts(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, countMap);
        collectGroupCounts(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, countMap);
        collectGroupCounts(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, countMap);
        collectDocumentGroupCounts(context, countMap);

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(countMap.entrySet());
        Collections.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<FileGroup> groups = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) {
            groups.add(new FileGroup(e.getKey(), e.getValue()));
        }
        return groups;
    }

    /** 查询某精确路径下的所有文件（不递归子目录） */
    public static List<MediaFile> listInGroup(Context context, String relativePath) {
        return listFilesInExactPath(context, relativePath);
    }

    // ========== 核心实现 ==========

    /** 收集所有 RELATIVE_PATH → 文件数（IMAGE/VIDEO/AUDIO/DOCUMENT 四类合并） */
    private static void collectAllPathCounts(Context context, Map<String, Integer> countMap) {
        collectGroupCounts(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, countMap);
        collectGroupCounts(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, countMap);
        collectGroupCounts(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, countMap);
        collectDocumentGroupCounts(context, countMap);
    }

    /** 从 pathFileCount 构建完整目录树 */
    private static DirectoryNode buildDirectoryTree(Map<String, Integer> pathFileCount) {
        DirectoryNode root = new DirectoryNode("", "", 0, 0);

        // 按路径长度排序，先处理短路径（上层目录）
        List<String> sortedPaths = new ArrayList<>(pathFileCount.keySet());
        sortedPaths.sort((a, b) -> {
            int la = countSlashes(a);
            int lb = countSlashes(b);
            if (la != lb) return Integer.compare(la, lb);
            return a.compareTo(b);
        });

        for (String path : sortedPaths) {
            String[] parts = path.split("/");
            DirectoryNode current = root;
            // 逐层创建/查找中间节点
            StringBuilder accPath = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part.isEmpty()) continue;
                if (accPath.length() > 0) accPath.append("/");
                accPath.append(part);
                String fullPath = accPath.toString();

                // 查找子节点
                DirectoryNode child = findChildByName(current, part);
                if (child == null) {
                    child = new DirectoryNode(fullPath, part, 0, 0);
                    // 保持 children 按名字排序
                    insertSorted(current.children, child);
                }
                current = child;
            }
            // 到达最终节点，设置直接文件数
            int directCount = pathFileCount.getOrDefault(path, 0);
            // 如果该路径的文件数已经全部归属到这个节点（没有更长路径以它为前缀且多一层）
            // pathFileCount 里 "DCIM/Camera" 就是 DCIM/Camera 下直接文件
            current.directFileCount += directCount;
        }

        // 后序遍历计算 totalFileCount
        computeTotalCounts(root);

        return root;
    }

    /** 后序遍历：totalFileCount = directFileCount + Σ child.totalFileCount */
    private static void computeTotalCounts(DirectoryNode node) {
        int total = node.directFileCount;
        for (DirectoryNode child : node.children) {
            computeTotalCounts(child);
            total += child.totalFileCount;
        }
        node.totalFileCount = total;
    }

    private static DirectoryNode findChildByName(DirectoryNode parent, String name) {
        for (DirectoryNode child : parent.children) {
            if (child.name.equals(name)) return child;
        }
        return null;
    }

    private static void insertSorted(List<DirectoryNode> list, DirectoryNode node) {
        int i = 0;
        while (i < list.size() && list.get(i).name.compareToIgnoreCase(node.name) < 0) i++;
        list.add(i, node);
    }

    private static int countSlashes(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '/') c++;
        return c;
    }

    private static DirectoryNode findNode(DirectoryNode root, String fullPath) {
        if (fullPath == null || fullPath.isEmpty() || "/".equals(fullPath)) return root;
        String[] parts = fullPath.split("/");
        DirectoryNode current = root;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            DirectoryNode child = findChildByName(current, part);
            if (child == null) return null;
            current = child;
        }
        return current;
    }

    /** 查询某精确路径下的直接文件（兼容尾部斜杠） */
    private static List<MediaFile> listFilesInExactPath(Context context, String relativePath) {
        if (context == null) return new ArrayList<>();
        List<MediaFile> result = new ArrayList<>();
        collectFilesInGroup(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "IMAGE", relativePath, result);
        collectFilesInGroup(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "VIDEO", relativePath, result);
        collectFilesInGroup(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "AUDIO", relativePath, result);
        collectDocumentFilesInGroup(context, relativePath, result);

        Collections.sort(result, (a, b) -> {
            long ta = a.takenAt > 0 ? a.takenAt : a.modifiedAt;
            long tb = b.takenAt > 0 ? b.takenAt : b.modifiedAt;
            return Long.compare(tb, ta);
        });
        return result;
    }

    // ========== MediaStore 查询辅助 ==========

    private static String buildPathSelection(String relativePath, List<String> argsOut) {
        argsOut.clear();
        String normalized = normalizePath(relativePath);

        StringBuilder sel = new StringBuilder("(");
        sel.append(MediaStore.MediaColumns.RELATIVE_PATH).append(" = ?");
        argsOut.add(normalized);
        if (!"/".equals(normalized)) {
            sel.append(" OR ").append(MediaStore.MediaColumns.RELATIVE_PATH).append(" = ?");
            argsOut.add(normalized + "/");
        } else {
            sel.append(" OR ").append(MediaStore.MediaColumns.RELATIVE_PATH).append(" = ?");
            argsOut.add("");
            sel.append(" OR ").append(MediaStore.MediaColumns.RELATIVE_PATH).append(" IS NULL");
        }
        sel.append(")");
        return sel.toString();
    }

    private static void collectGroupCounts(Context context, Uri collection,
                                           Map<String, Integer> countMap) {
        String[] projection = {
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
        };
        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, null, null, null)) {
            if (cursor == null) return;
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameCol);
                if (name == null || name.isEmpty()) continue;
                String path = cursor.getString(pathCol);
                if (path == null || path.isEmpty()) path = "/";
                countMap.merge(normalizePath(path), 1, Integer::sum);
            }
        } catch (Exception e) {
            Log.e(TAG, "collectGroupCounts failed for " + collection, e);
        }
    }

    private static void collectDocumentGroupCounts(Context context, Map<String, Integer> countMap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        StringBuilder selection = new StringBuilder();
        List<String> args = new ArrayList<>();
        for (int i = 0; i < DocumentTypes.EXTENSIONS.size(); i++) {
            if (i > 0) selection.append(" OR ");
            selection.append("LOWER(").append(MediaStore.MediaColumns.DISPLAY_NAME).append(") LIKE ?");
            args.add("%." + DocumentTypes.EXTENSIONS.get(i));
        }
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Files.getContentUri("external"),
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH},
                selection.toString(), args.toArray(new String[0]), null)) {
            if (cursor == null) return;
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameCol);
                if (name == null || name.isEmpty()) continue;
                String path = cursor.getString(pathCol);
                if (path == null || path.isEmpty()) path = "/";
                countMap.merge(normalizePath(path), 1, Integer::sum);
            }
        } catch (Exception ignored) {
        }
    }

    private static void collectFilesInGroup(Context context, Uri collection, String mediaType,
                                            String relativePath, List<MediaFile> result) {
        List<String> pathArgs = new ArrayList<>();
        String pathSelection = buildPathSelection(relativePath, pathArgs);

        boolean imageType = "IMAGE".equals(mediaType);
        boolean videoType = "VIDEO".equals(mediaType);
        boolean useTaken = imageType || videoType;

        String[] projection = useTaken
                ? new String[]{
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                imageType ? MediaStore.Images.Media.DATE_TAKEN : MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH
        }
                : new String[]{
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH
        };

        Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, pathSelection);
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                pathArgs.toArray(new String[0]));
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,
                new String[]{MediaStore.MediaColumns.DATE_MODIFIED});
        queryArgs.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);

        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, queryArgs, null)) {
            if (cursor == null) return;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
            int takenCol = useTaken
                    ? cursor.getColumnIndexOrThrow(imageType
                    ? MediaStore.Images.Media.DATE_TAKEN
                    : MediaStore.Video.Media.DATE_TAKEN) : -1;

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                if (name == null || name.isEmpty()) continue;
                long modified = cursor.getLong(modCol) * 1000L;
                long taken = useTaken && takenCol >= 0 ? cursor.getLong(takenCol) : 0L;
                if (taken <= 0) taken = modified;
                long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : 0;
                String path = cursor.getString(pathCol);
                Uri uri = ContentUris.withAppendedId(collection, id);
                result.add(new MediaFile(uri, id, name, mediaType, size, modified, taken, path));
            }
        } catch (Exception e) {
            Log.e(TAG, "collectFilesInGroup failed for " + mediaType, e);
        }
    }

    private static void collectDocumentFilesInGroup(Context context, String relativePath,
                                                    List<MediaFile> result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        List<String> pathArgs = new ArrayList<>();
        String pathSelection = buildPathSelection(relativePath, pathArgs);

        StringBuilder selection = new StringBuilder("(");
        List<String> args = new ArrayList<>();
        for (int i = 0; i < DocumentTypes.EXTENSIONS.size(); i++) {
            if (i > 0) selection.append(" OR ");
            selection.append("LOWER(").append(MediaStore.MediaColumns.DISPLAY_NAME).append(") LIKE ?");
            args.add("%." + DocumentTypes.EXTENSIONS.get(i));
        }
        selection.append(") AND ").append(pathSelection);
        args.addAll(pathArgs);

        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Files.getContentUri("external"),
                new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.RELATIVE_PATH},
                selection.toString(), args.toArray(new String[0]),
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                if (name == null || name.isEmpty()) continue;
                long modified = cursor.getLong(modCol) * 1000L;
                long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : 0;
                String path = cursor.getString(pathCol);
                Uri uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id);
                result.add(new MediaFile(uri, id, name, "DOCUMENT", size, modified, modified, path));
            }
        } catch (Exception ignored) {
        }
    }

    private static String normalizePath(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "/";
        return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
    }
}
