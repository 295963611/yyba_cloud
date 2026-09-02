package com.yyba.cloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyba.cloud.config.SqliteWriteGuard;
import com.yyba.cloud.entity.CloudFile;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.mapper.CloudFileMapper;
import com.yyba.cloud.util.DocumentTypes;
import com.yyba.cloud.util.MediaTypeUtil;
import com.yyba.cloud.util.PathUtil;
import com.yyba.cloud.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 将用户存储目录中「磁盘有、数据库无」的文件补写入 {@code files} 表。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiskIndexService {

    private static final Set<String> SKIP_DIR_NAMES = Set.of(".thumbs", ".chunks", ".trash");
    private static final long RECONCILE_INTERVAL_MS = 60_000;

    private final ConcurrentHashMap<String, Long> lastReconcileMs = new ConcurrentHashMap<>();

    private final CloudFileMapper cloudFileMapper;
    private final ConfigService configService;
    private final ThumbnailService thumbnailService;
    private final SqliteWriteGuard sqliteWriteGuard;

    /**
     * 若数据库无有效记录但磁盘存在该文件，则自动入库。
     */
    public CloudFile indexIfMissing(User user, String relativePath) {
        String normalized = PathUtil.normalizeRelative(relativePath);
        CloudFile existing = findAnyByUserAndPath(user.getId(), normalized);
        if (existing != null) {
            return existing.getDeletedAt() == null ? existing : null;
        }
        Path userRoot = PathUtil.userRoot(configService.getStorageRoot(), user.getUsername());
        Path filePath = PathUtil.resolveUserFile(configService.getStorageRoot(), user.getUsername(), normalized);
        if (!Files.isRegularFile(filePath)) {
            return null;
        }
        if (shouldSkipRelative(normalized)) {
            return null;
        }
        return createRecordFromDisk(user, filePath, normalized);
    }

    /**
     * 扫描用户媒体目录，补录缺失的数据库记录。
     *
     * @param category PHOTO / VIDEO / AUDIO / ALL
     * @return 新入库文件数
     */
    public int reconcileUserMedia(User user, String category) {
        return reconcileUserMediaInternal(user, category, false);
    }

    /** 强制全量扫描（忽略缓存），供启动任务与手动触发使用 */
    public int reconcileUserMediaForce(User user, String category) {
        return reconcileUserMediaInternal(user, category, true);
    }

    private int reconcileUserMediaInternal(User user, String category, boolean force) {
        String key = user.getId() + ":" + (category == null ? "ALL" : category.toUpperCase());
        if (!force) {
            long now = System.currentTimeMillis();
            Long last = lastReconcileMs.get(key);
            if (last != null && now - last < RECONCILE_INTERVAL_MS) {
                return 0;
            }
            lastReconcileMs.put(key, now);
        } else {
            lastReconcileMs.put(key, System.currentTimeMillis());
        }

        Path userRoot = PathUtil.userRoot(configService.getStorageRoot(), user.getUsername());
        if (!Files.isDirectory(userRoot)) {
            return 0;
        }
        List<Path> scanRoots = mediaScanRoots(userRoot, category);
        int indexed = 0;
        for (Path root : scanRoots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            indexed += walkAndIndex(user, userRoot, root);
        }
        if (indexed > 0) {
            log.info("磁盘索引补录 user={} category={} count={}", user.getUsername(), category, indexed);
        }
        return indexed;
    }

    private int walkAndIndex(User user, Path userRoot, Path dir) {
        int count = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .toList();
            for (Path file : files) {
                String relative = userRoot.relativize(file).toString().replace('\\', '/');
                if (shouldSkipRelative(relative)) {
                    continue;
                }
                CloudFile existing = findAnyByUserAndPath(user.getId(), relative);
                if (existing != null) {
                    continue;
                }
                createRecordFromDisk(user, file, relative);
                count++;
            }
        } catch (IOException e) {
            log.warn("扫描目录失败 user={} dir={}: {}", user.getUsername(), dir, e.getMessage());
        }
        return count;
    }

    private CloudFile createRecordFromDisk(User user, Path filePath, String normalized) {
        try {
            String fileName = filePath.getFileName().toString();
            long size = Files.size(filePath);
            String mimeType = Files.probeContentType(filePath);
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = MediaTypeUtil.guessMimeFromPath(normalized);
            }
            String capturedAt = TimeUtil.fromEpochMs(resolveCapturedEpochMs(filePath));
            String parentPath = FilenameUtils.getPath(normalized);
            if (parentPath.endsWith("/")) {
                parentPath = parentPath.substring(0, parentPath.length() - 1);
            }
            String now = TimeUtil.now();
            CloudFile file = new CloudFile();
            file.setUserId(user.getId());
            file.setParentPath(parentPath);
            file.setFileName(fileName);
            file.setRelativePath(normalized);
            file.setFileSize(size);
            file.setMimeType(mimeType);
            file.setSha256(null);
            file.setFileType(detectFileType(mimeType, fileName));
            file.setSource("DISK_IMPORT");
            file.setBackupStatus("PENDING");
            file.setCapturedAt(capturedAt);
            file.setShare(0);
            file.setCreatedAt(now);
            file.setUpdatedAt(now);
            sqliteWriteGuard.write(() -> cloudFileMapper.insert(file));
            generateThumbnailIfPossible(user, file, filePath);
            return file;
        } catch (IOException e) {
            log.warn("入库磁盘文件失败 path={}: {}", normalized, e.getMessage());
            return null;
        }
    }

    /** 无拍摄元数据时使用文件创建时间，不可用时回退最后修改时间 */
    private long resolveCapturedEpochMs(Path filePath) throws IOException {
        try {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            FileTime creation = attrs.creationTime();
            if (creation != null) {
                long ms = creation.toMillis();
                if (ms > 0) {
                    return ms;
                }
            }
        } catch (IOException ignored) {
        }
        return Files.getLastModifiedTime(filePath).toMillis();
    }

    private List<Path> mediaScanRoots(Path userRoot, String category) {
        String type = category == null || category.isBlank() ? "ALL" : category.toUpperCase();
        List<Path> roots = new ArrayList<>();
        switch (type) {
            case "PHOTO" -> roots.add(userRoot.resolve("photos"));
            case "VIDEO" -> roots.add(userRoot.resolve("videos"));
            case "AUDIO" -> {
                roots.add(userRoot.resolve("audio"));
                roots.add(userRoot.resolve("uploads/audio"));
            }
            case "ALL" -> {
                roots.add(userRoot.resolve("photos"));
                roots.add(userRoot.resolve("videos"));
                roots.add(userRoot.resolve("audio"));
                roots.add(userRoot.resolve("uploads/audio"));
            }
            default -> roots.add(userRoot.resolve("photos"));
        }
        return roots;
    }

    private CloudFile findAnyByUserAndPath(Long userId, String relativePath) {
        return cloudFileMapper.selectOne(new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, userId)
                .eq(CloudFile::getRelativePath, relativePath));
    }

    private boolean shouldSkipRelative(String relative) {
        if (relative == null || relative.isBlank()) {
            return true;
        }
        if (relative.startsWith(".")) {
            return true;
        }
        for (String part : relative.split("/")) {
            if (part.startsWith(".") || SKIP_DIR_NAMES.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private String detectFileType(String mimeType, String fileName) {
        if (mimeType != null) {
            if (mimeType.startsWith("image/")) {
                return "PHOTO";
            }
            if (mimeType.startsWith("video/")) {
                return "VIDEO";
            }
            if (mimeType.startsWith("audio/")) {
                return "AUDIO";
            }
        }
        String ext = FilenameUtils.getExtension(fileName).toLowerCase();
        if (List.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif").contains(ext)) {
            return "PHOTO";
        }
        if (List.of("mp4", "avi", "mov", "mkv", "3gp").contains(ext)) {
            return "VIDEO";
        }
        if (List.of("mp3", "flac", "wav", "aac", "m4a", "ogg", "wma").contains(ext)) {
            return "AUDIO";
        }
        if (DocumentTypes.isDocumentExtension(fileName)) {
            return "DOCUMENT";
        }
        return "OTHER";
    }

    private void generateThumbnailIfPossible(User user, CloudFile file, Path filePath) {
        try {
            String thumbRelative = null;
            if (thumbnailService.isPhoto(file.getMimeType(), file.getFileName())) {
                thumbRelative = thumbnailService.generatePhotoThumbnail(
                        filePath, user, file.getRelativePath(), file.getMimeType(), file.getFileName());
            } else if (thumbnailService.isVideo(file.getMimeType(), file.getFileName())) {
                thumbRelative = thumbnailService.generateVideoThumbnail(
                        filePath, user, file.getRelativePath(), file.getMimeType(), file.getFileName());
            }
            if (thumbRelative != null) {
                file.setThumbnailPath(thumbRelative);
                file.setUpdatedAt(TimeUtil.now());
                sqliteWriteGuard.write(() -> cloudFileMapper.updateById(file));
            }
        } catch (Exception e) {
            log.warn("磁盘补录缩略图失败 path={}: {}", file.getRelativePath(), e.getMessage());
        }
    }
}
