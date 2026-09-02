package com.yyba.cloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyba.cloud.config.SqliteWriteGuard;
import com.yyba.cloud.dto.*;
import com.yyba.cloud.entity.CloudFile;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.mapper.CloudFileMapper;
import com.yyba.cloud.mapper.UserMapper;
import com.yyba.cloud.util.DocumentTypes;
import com.yyba.cloud.util.PathUtil;
import com.yyba.cloud.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

/**
 * 云文件业务服务。
 * <p>管理 {@code files} 表与磁盘文件的映射：目录浏览、同步索引、下载、软删除与入库。</p>
 */
@Service
@RequiredArgsConstructor
public class FileService {

    private static final Set<String> SHAREABLE_TYPES = Set.of("PHOTO", "VIDEO", "AUDIO");

    private final CloudFileMapper cloudFileMapper;
    private final UserMapper userMapper;
    private final ConfigService configService;
    private final OperationLogService operationLogService;
    private final ThumbnailService thumbnailService;
    private final DiskIndexService diskIndexService;
    private final SqliteWriteGuard sqliteWriteGuard;

    /** 列出目录内容（合并磁盘子目录与数据库文件记录） */
    public List<FileItemDto> listFiles(User user, String path) {
        String parentPath = PathUtil.normalizeRelative(path);
        Path userRoot = PathUtil.userRoot(configService.getStorageRoot(), user.getUsername());
        Path current = parentPath.isEmpty() ? userRoot : userRoot.resolve(parentPath);
        if (!Files.exists(current)) {
            return List.of();
        }

        Set<String> subDirs = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(current)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .forEach(subDirs::add);
        } catch (IOException e) {
            throw new IllegalStateException("读取目录失败");
        }

        List<CloudFile> dbFiles = cloudFileMapper.selectList(new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, user.getId())
                .eq(CloudFile::getParentPath, parentPath)
                .isNull(CloudFile::getDeletedAt)
                .orderByAsc(CloudFile::getFileName));

        for (CloudFile file : dbFiles) {
            String dir = extractImmediateChildDir(parentPath, file.getRelativePath());
            if (dir != null) {
                subDirs.add(dir);
            }
        }

        List<FileItemDto> result = new ArrayList<>();
        for (String dir : subDirs) {
            String relative = parentPath.isEmpty() ? dir : parentPath + "/" + dir;
            result.add(FileItemDto.builder()
                    .name(dir)
                    .relativePath(relative)
                    .directory(true)
                    .build());
        }

        for (CloudFile file : dbFiles) {
            if (isDirectChild(parentPath, file.getRelativePath())) {
                result.add(toFileItem(file));
            }
        }

        Set<String> listedPaths = new HashSet<>();
        for (CloudFile file : dbFiles) {
            if (isDirectChild(parentPath, file.getRelativePath())) {
                listedPaths.add(file.getRelativePath());
            }
        }
        try (Stream<Path> stream = Files.list(current)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                String relative = parentPath.isEmpty() ? name : parentPath + "/" + name;
                if (listedPaths.contains(relative)) {
                    return;
                }
                CloudFile indexed = diskIndexService.indexIfMissing(user, relative);
                if (indexed != null) {
                    result.add(toFileItem(indexed));
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("读取目录失败");
        }
        return result;
    }

    private FileItemDto toFileItem(CloudFile file) {
        return FileItemDto.builder()
                .id(file.getId())
                .name(file.getFileName())
                .relativePath(file.getRelativePath())
                .directory(false)
                .size(file.getFileSize())
                .mimeType(file.getMimeType())
                .fileType(file.getFileType())
                .thumbnailPath(file.getThumbnailPath())
                .createdAt(file.getCreatedAt())
                .deletedAt(file.getDeletedAt())
                .build();
    }

    /** 获取磁盘文件资源，供下载接口流式返回 */
    public Resource getFileResource(User viewer, String relativePath, Long ownerUserId) {
        AccessibleFile accessible = resolveAccessibleFile(viewer, ownerUserId, relativePath);
        Path filePath = resolvePhysicalFilePath(accessible.owner(), accessible.file());
        if (filePath == null || !Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("文件不存在");
        }
        return new FileSystemResource(filePath);
    }

    /** 获取媒体缩略图资源；若尚未生成则按需懒生成并回写数据库 */
    public Resource getThumbnailResource(User viewer, String relativePath, Long ownerUserId) {
        AccessibleFile accessible = resolveAccessibleFile(viewer, ownerUserId, relativePath);
        CloudFile cloudFile = accessible.file();
        User owner = accessible.owner();

        Path thumbPath = resolveThumbnailPath(owner, cloudFile);
        if (thumbPath != null) {
            return new FileSystemResource(thumbPath);
        }

        Path source = resolvePhysicalFilePath(owner, cloudFile);
        if (source == null) {
            throw new IllegalArgumentException("无法生成缩略图");
        }

        String thumbRelative = null;
        if (isImageFile(cloudFile)) {
            thumbRelative = thumbnailService.generatePhotoThumbnail(
                    source, owner, accessible.normalizedPath(),
                    cloudFile.getMimeType(), cloudFile.getFileName());
        } else if (isVideoFile(cloudFile)) {
            thumbRelative = thumbnailService.generateVideoThumbnail(
                    source, owner, accessible.normalizedPath(),
                    cloudFile.getMimeType(), cloudFile.getFileName());
        } else {
            throw new IllegalArgumentException("该文件不支持缩略图");
        }

        if (thumbRelative == null) {
            throw new IllegalArgumentException("无法生成缩略图");
        }
        final String persistedThumb = thumbRelative;
        sqliteWriteGuard.write(() -> {
            cloudFile.setThumbnailPath(persistedThumb);
            cloudFile.setUpdatedAt(TimeUtil.now());
            cloudFileMapper.updateById(cloudFile);
        });
        thumbPath = configService.getStorageRoot().resolve(persistedThumb);
        if (!Files.exists(thumbPath)) {
            throw new IllegalArgumentException("缩略图不存在");
        }
        return new FileSystemResource(thumbPath);
    }

    private boolean isVideoFile(CloudFile cloudFile) {
        if (cloudFile == null) {
            return false;
        }
        String type = cloudFile.getFileType();
        if (type != null && "VIDEO".equalsIgnoreCase(type)) {
            return true;
        }
        return thumbnailService.isVideo(cloudFile.getMimeType(), cloudFile.getFileName());
    }

    private boolean isImageFile(CloudFile cloudFile) {
        if (cloudFile == null) {
            return false;
        }
        String type = cloudFile.getFileType();
        if (type != null) {
            String upper = type.toUpperCase();
            if ("PHOTO".equals(upper) || "IMAGE".equals(upper)) {
                return true;
            }
        }
        String mime = cloudFile.getMimeType();
        return mime != null && mime.toLowerCase().startsWith("image/");
    }

    /** 扫描磁盘并补录缺失的数据库记录 */
    public int reconcileDisk(User user, String category) {
        return diskIndexService.reconcileUserMediaForce(user, category);
    }

    public CloudFile getById(Long id) {
        return id == null ? null : cloudFileMapper.selectById(id);
    }

    public void updateThumbnailPath(Long fileId, String thumbnailPath) {
        if (fileId == null || thumbnailPath == null || thumbnailPath.isBlank()) {
            return;
        }
        sqliteWriteGuard.write(() -> {
            CloudFile file = cloudFileMapper.selectById(fileId);
            if (file == null) {
                return;
            }
            file.setThumbnailPath(thumbnailPath);
            file.setUpdatedAt(TimeUtil.now());
            cloudFileMapper.updateById(file);
        });
    }

    /** 移入回收站（保留磁盘文件至 .trash 目录） */
    @Transactional
    public void deleteFile(User user, String relativePath, String clientIp) {
        String normalized = PathUtil.normalizeRelative(relativePath);
        CloudFile cloudFile = findIncludingDeleted(user.getId(), normalized);
        Path filePath = PathUtil.resolveUserFile(configService.getStorageRoot(), user.getUsername(), normalized);
        if (cloudFile != null && cloudFile.getDeletedAt() != null) {
            throw new IllegalArgumentException("文件已在回收站");
        }
        if (cloudFile == null) {
            if (!Files.isRegularFile(filePath)) {
                throw new IllegalArgumentException("文件不存在");
            }
            // 磁盘有文件但数据库无记录时，先补录入库再标记回收站，否则回收站列表不可见
            cloudFile = diskIndexService.indexIfMissing(user, normalized);
            if (cloudFile == null) {
                throw new IllegalStateException("无法登记文件记录");
            }
        }

        if (Files.isRegularFile(filePath)) {
            moveToTrash(user, filePath, normalized);
        }
        if (cloudFile.getThumbnailPath() != null && !cloudFile.getThumbnailPath().isBlank()) {
            Path thumbPath = configService.getStorageRoot().resolve(cloudFile.getThumbnailPath());
            if (Files.isRegularFile(thumbPath)) {
                moveToTrash(user, thumbPath, cloudFile.getThumbnailPath());
            }
        }
        String now = TimeUtil.now();
        cloudFile.setDeletedAt(now);
        cloudFile.setUpdatedAt(now);
        cloudFile.setShare(0);
        cloudFileMapper.updateById(cloudFile);
        operationLogService.log(user.getId(), "DELETE", normalized, clientIp, null);
    }

    /** 列出回收站文件 */
    public List<FileItemDto> listTrash(User user) {
        List<CloudFile> files = cloudFileMapper.selectList(new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, user.getId())
                .isNotNull(CloudFile::getDeletedAt)
                .orderByDesc(CloudFile::getDeletedAt));
        List<FileItemDto> result = new ArrayList<>(files.size());
        for (CloudFile file : files) {
            result.add(toFileItem(file));
        }
        return result;
    }

    /** 从回收站恢复 */
    @Transactional
    public void restoreFile(User user, String relativePath, String clientIp) {
        String normalized = PathUtil.normalizeRelative(relativePath);
        CloudFile cloudFile = findIncludingDeleted(user.getId(), normalized);
        if (cloudFile == null || cloudFile.getDeletedAt() == null) {
            throw new IllegalArgumentException("回收站中不存在该文件");
        }
        try {
            Path trashPath = trashFilePath(user.getUsername(), normalized);
            Path restorePath = PathUtil.resolveUserFile(
                    configService.getStorageRoot(), user.getUsername(), normalized);
            if (Files.isRegularFile(trashPath)) {
                Files.createDirectories(restorePath.getParent());
                Files.move(trashPath, restorePath, StandardCopyOption.REPLACE_EXISTING);
            }
            if (cloudFile.getThumbnailPath() != null && !cloudFile.getThumbnailPath().isBlank()) {
                Path trashThumb = trashFilePath(user.getUsername(), cloudFile.getThumbnailPath());
                Path restoreThumb = configService.getStorageRoot().resolve(cloudFile.getThumbnailPath());
                if (Files.isRegularFile(trashThumb)) {
                    Files.createDirectories(restoreThumb.getParent());
                    Files.move(trashThumb, restoreThumb, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("恢复文件失败");
        }
        cloudFile.setDeletedAt(null);
        cloudFile.setUpdatedAt(TimeUtil.now());
        cloudFileMapper.updateById(cloudFile);
        operationLogService.log(user.getId(), "RESTORE", normalized, clientIp, null);
    }

    /** 从回收站永久删除（不可恢复） */
    @Transactional
    public void permanentDeleteFromTrash(User user, String relativePath, String clientIp) {
        String normalized = PathUtil.normalizeRelative(relativePath);
        CloudFile cloudFile = findIncludingDeleted(user.getId(), normalized);
        if (cloudFile == null || cloudFile.getDeletedAt() == null) {
            throw new IllegalArgumentException("回收站中不存在该文件");
        }
        permanentlyDeleteTrashFile(user.getUsername(), cloudFile);
        cloudFileMapper.deleteById(cloudFile.getId());
        operationLogService.log(user.getId(), "PERMANENT_DELETE", normalized, clientIp, null);
    }

    /** 永久清理超过保留期的回收站文件 */
    @Transactional
    public int purgeExpiredTrash() {
        int retentionDays = configService.getTrashRetentionDays();
        String cutoff = TimeUtil.fromEpochMs(
                Instant.now().minus(retentionDays, ChronoUnit.DAYS).toEpochMilli());
        List<CloudFile> expired = cloudFileMapper.selectList(new LambdaQueryWrapper<CloudFile>()
                .isNotNull(CloudFile::getDeletedAt)
                .lt(CloudFile::getDeletedAt, cutoff));
        int purged = 0;
        for (CloudFile file : expired) {
            User owner = userMapper.selectById(file.getUserId());
            if (owner == null) {
                cloudFileMapper.deleteById(file.getId());
                purged++;
                continue;
            }
            permanentlyDeleteTrashFile(owner.getUsername(), file);
            cloudFileMapper.deleteById(file.getId());
            purged++;
        }
        return purged;
    }

    private void permanentlyDeleteTrashFile(String username, CloudFile file) {
        try {
            Path trashPath = trashFilePath(username, file.getRelativePath());
            Files.deleteIfExists(trashPath);
            if (file.getThumbnailPath() != null && !file.getThumbnailPath().isBlank()) {
                Files.deleteIfExists(trashFilePath(username, file.getThumbnailPath()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("清理回收站失败: " + file.getRelativePath());
        }
    }

    private void moveToTrash(User user, Path source, String normalized) {
        try {
            Path target = trashFilePath(user.getUsername(), normalized);
            Files.createDirectories(target.getParent());
            if (Files.exists(source)) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("移入回收站失败");
        }
    }

    private Path trashFilePath(String username, String normalized) {
        return configService.getStorageRoot()
                .resolve(".trash")
                .resolve(username)
                .resolve(PathUtil.normalizeRelative(normalized));
    }

    private CloudFile findIncludingDeleted(Long userId, String relativePath) {
        return cloudFileMapper.selectOne(new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, userId)
                .eq(CloudFile::getRelativePath, relativePath));
    }

    /** 上传合并完成后写入或更新 files 表记录 */
    @Transactional
    public CloudFile saveFileRecord(User user, String relativePath, String fileName, long size,
                                    String mimeType, String sha256, String source, String thumbnailPath,
                                    String capturedAt) {
        String normalized = PathUtil.normalizeRelative(relativePath);
        String parentPath = FilenameUtils.getPath(normalized);
        if (parentPath.endsWith("/")) {
            parentPath = parentPath.substring(0, parentPath.length() - 1);
        }

        CloudFile file = findByUserAndPath(user.getId(), normalized);
        String now = TimeUtil.now();
        if (file != null) {
            file.setFileSize(size);
            file.setMimeType(mimeType);
            file.setSha256(sha256);
            file.setUpdatedAt(now);
            file.setBackupStatus("PENDING");
            if (thumbnailPath != null) {
                file.setThumbnailPath(thumbnailPath);
            }
            if (capturedAt != null) {
                file.setCapturedAt(capturedAt);
            }
            cloudFileMapper.updateById(file);
        } else {
            file = new CloudFile();
            file.setUserId(user.getId());
            file.setParentPath(parentPath);
            file.setFileName(fileName);
            file.setRelativePath(normalized);
            file.setFileSize(size);
            file.setMimeType(mimeType);
            file.setSha256(sha256);
            file.setFileType(detectFileType(mimeType, fileName));
            file.setSource(source);
            file.setBackupStatus("PENDING");
            file.setThumbnailPath(thumbnailPath);
            file.setCapturedAt(capturedAt != null ? capturedAt : now);
            file.setShare(0);
            file.setCreatedAt(now);
            file.setUpdatedAt(now);
            cloudFileMapper.insert(file);
        }
        return file;
    }

    /** 单张或批量更新分享状态 */
    @Transactional
    public int updateShareStatus(User user, List<String> paths, int share, String clientIp) {
        if (paths == null || paths.isEmpty()) {
            return 0;
        }
        if (share != 0 && share != 1) {
            throw new IllegalArgumentException("share 只能为 0 或 1");
        }
        String now = TimeUtil.now();
        int updated = sqliteWriteGuard.write(() -> {
            int count = 0;
            for (String path : paths) {
                String normalized = PathUtil.normalizeRelative(path);
                CloudFile file = findByUserAndPath(user.getId(), normalized);
                if (file == null || !isShareableMedia(file)) {
                    continue;
                }
                file.setShare(share);
                file.setUpdatedAt(now);
                cloudFileMapper.updateById(file);
                count++;
                operationLogService.log(user.getId(), share == 1 ? "SHARE" : "UNSHARE", normalized, clientIp, null);
            }
            return count;
        });
        return updated;
    }

    /** 一键分享/取消当前用户某类型全部媒体 */
    @Transactional
    public int updateShareAll(User user, String fileType, int share, String clientIp) {
        if (share != 0 && share != 1) {
            throw new IllegalArgumentException("share 只能为 0 或 1");
        }
        String type = resolveMediaCategory(fileType);
        LambdaQueryWrapper<CloudFile> wrapper = new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, user.getId())
                .isNull(CloudFile::getDeletedAt);
        applySharedCategoryFilter(wrapper, type);
        List<CloudFile> files = cloudFileMapper.selectList(wrapper);
        String now = TimeUtil.now();
        int updated = sqliteWriteGuard.write(() -> {
            int count = 0;
            for (CloudFile file : files) {
                file.setShare(share);
                file.setUpdatedAt(now);
                cloudFileMapper.updateById(file);
                count++;
            }
            return count;
        });
        if (updated > 0) {
            operationLogService.log(user.getId(), share == 1 ? "SHARE_ALL" : "UNSHARE_ALL",
                    type, clientIp, "count=" + updated);
        }
        return updated;
    }

    /** 家庭/我分享：按拍摄时间分页查询已分享媒体（PHOTO/VIDEO/AUDIO 分栏） */
    public AlbumPhotoPageDto listSharedMedia(User viewer, String scope, String fileType,
                                             long beforeTime, int limit) {
        String category = resolveMediaCategory(fileType);
        int pageSize = Math.min(Math.max(limit, 1), 200);
        String beforeIso = TimeUtil.fromEpochMs(beforeTime);

        LambdaQueryWrapper<CloudFile> wrapper = new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getShare, 1)
                .isNull(CloudFile::getDeletedAt)
                .lt(CloudFile::getCapturedAt, beforeIso)
                .orderByDesc(CloudFile::getCapturedAt)
                .last("LIMIT " + pageSize);
        applySharedCategoryFilter(wrapper, category);

        if ("mine".equalsIgnoreCase(scope)) {
            wrapper.eq(CloudFile::getUserId, viewer.getId());
        } else if (!"family".equalsIgnoreCase(scope)) {
            throw new IllegalArgumentException("scope 只能为 family 或 mine");
        }

        List<CloudFile> files = cloudFileMapper.selectList(wrapper);
        Map<Long, User> owners = loadEnabledOwners(files);
        List<AlbumPhotoItemDto> items = new ArrayList<>();
        for (CloudFile file : files) {
            User owner = owners.get(file.getUserId());
            if (owner == null || !isActiveFileOnDisk(owner, file)) {
                continue;
            }
            items.add(toAlbumItem(file, owner));
        }
        return AlbumPhotoPageDto.builder()
                .items(items)
                .hasMore(files.size() >= pageSize)
                .build();
    }

    /** 相册/音频/视频按拍摄时间分页，返回 captured_at 严格小于 beforeTime 的记录 */
    public AlbumPhotoPageDto listAlbumPhotos(User user, String fileType, long beforeTime, int limit) {
        String type = resolveMediaCategory(fileType != null && !fileType.isBlank() ? fileType : "PHOTO");
        int pageSize = Math.min(Math.max(limit, 1), 200);
        String beforeIso = TimeUtil.fromEpochMs(beforeTime);

        LambdaQueryWrapper<CloudFile> wrapper = new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, user.getId())
                .isNull(CloudFile::getDeletedAt)
                .lt(CloudFile::getCapturedAt, beforeIso)
                .orderByDesc(CloudFile::getCapturedAt)
                .last("LIMIT " + pageSize);
        applyMediaCategoryFilter(wrapper, type);

        List<CloudFile> files = cloudFileMapper.selectList(wrapper);

        List<AlbumPhotoItemDto> items = new ArrayList<>(files.size());
        for (CloudFile file : files) {
            if (!isActiveFileOnDisk(user, file)) {
                continue;
            }
            items.add(toAlbumItem(file, user));
        }
        return AlbumPhotoPageDto.builder()
                .items(items)
                .hasMore(files.size() >= pageSize)
                .build();
    }

    private static final long CAPTURED_AT_TOLERANCE_MS = 2000L;
    private static final int VERIFY_SYNC_MAX_ITEMS = 50;

    /** 批量校验/懒重建：有 remoteFileId 校验有效性；否则按 fileHash 或 path+size+capturedAt 重建 sync_records */
    public VerifySyncResponse verifySync(User user, VerifySyncRequest request) {
        List<VerifySyncItemDto> items = normalizeVerifyItems(request);
        if (items.isEmpty()) {
            return VerifySyncResponse.builder()
                    .invalidIds(List.of())
                    .validItems(List.of())
                    .rebuiltRecords(List.of())
                    .build();
        }
        List<Long> invalidIds = new ArrayList<>();
        List<VerifySyncValidItemDto> validItems = new ArrayList<>();
        List<SyncRecordDto> rebuiltRecords = new ArrayList<>();
        for (VerifySyncItemDto item : items) {
            if (item == null) {
                continue;
            }
            Long remoteFileId = item.getRemoteFileId();
            if (remoteFileId != null && remoteFileId > 0) {
                verifyByRemoteFileId(user, remoteFileId, invalidIds, validItems);
            } else {
                SyncRecordDto rebuilt = rebuildByLocalMetadata(user, item);
                if (rebuilt != null) {
                    rebuiltRecords.add(rebuilt);
                }
            }
        }
        return VerifySyncResponse.builder()
                .invalidIds(invalidIds)
                .validItems(validItems)
                .rebuiltRecords(rebuiltRecords)
                .build();
    }

    private List<VerifySyncItemDto> normalizeVerifyItems(VerifySyncRequest request) {
        if (request == null) {
            return List.of();
        }
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            return request.getItems().stream()
                    .filter(Objects::nonNull)
                    .limit(VERIFY_SYNC_MAX_ITEMS)
                    .toList();
        }
        if (request.getFileIds() == null || request.getFileIds().isEmpty()) {
            return List.of();
        }
        List<VerifySyncItemDto> legacy = new ArrayList<>();
        for (Long fileId : request.getFileIds()) {
            if (fileId == null || fileId <= 0) {
                continue;
            }
            VerifySyncItemDto dto = new VerifySyncItemDto();
            dto.setRemoteFileId(fileId);
            legacy.add(dto);
            if (legacy.size() >= VERIFY_SYNC_MAX_ITEMS) {
                break;
            }
        }
        return legacy;
    }

    private void verifyByRemoteFileId(User user, long fileId, List<Long> invalidIds,
                                    List<VerifySyncValidItemDto> validItems) {
        CloudFile file = cloudFileMapper.selectById(fileId);
        if (file == null || !user.getId().equals(file.getUserId()) || !isActiveFileOnDisk(user, file)) {
            invalidIds.add(fileId);
        } else {
            validItems.add(VerifySyncValidItemDto.builder()
                    .fileId(file.getId())
                    .relativePath(file.getRelativePath())
                    .share(file.getShare())
                    .build());
        }
    }

    private SyncRecordDto rebuildByLocalMetadata(User user, VerifySyncItemDto item) {
        SyncRecordDto byHash = rebuildByFileHash(user, item);
        if (byHash != null) {
            return byHash;
        }
        return rebuildByPathSizeCapturedAt(user, item);
    }

    private SyncRecordDto rebuildByFileHash(User user, VerifySyncItemDto item) {
        String hash = item.getFileHash();
        if (hash == null || hash.isBlank()) {
            return null;
        }
        Optional<CloudFile> optional = findByHash(user, hash.trim());
        if (optional.isEmpty()) {
            return null;
        }
        CloudFile matched = optional.get();
        if (!isActiveFileOnDisk(user, matched)) {
            return null;
        }
        String clientPath = PathUtil.normalizeRelative(item.getRelativePath());
        if (clientPath != null && !clientPath.isEmpty()
                && !clientPath.equals(matched.getRelativePath())) {
            return null;
        }
        return toRebuiltSyncRecord(item, matched);
    }

    private SyncRecordDto rebuildByPathSizeCapturedAt(User user, VerifySyncItemDto item) {
        String relativePath = PathUtil.normalizeRelative(item.getRelativePath());
        Long fileSize = item.getFileSize();
        Long capturedAt = item.getCapturedAt();
        if (relativePath == null || relativePath.isEmpty() || fileSize == null || fileSize <= 0
                || capturedAt == null || capturedAt <= 0) {
            return null;
        }
        List<CloudFile> candidates = cloudFileMapper.selectList(new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, user.getId())
                .eq(CloudFile::getRelativePath, relativePath)
                .eq(CloudFile::getFileSize, fileSize)
                .isNull(CloudFile::getDeletedAt));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        CloudFile matched = null;
        for (CloudFile candidate : candidates) {
            if (!capturedAtMatches(capturedAt, candidate.getCapturedAt())) {
                continue;
            }
            if (!isActiveFileOnDisk(user, candidate)) {
                continue;
            }
            if (matched == null || effectiveUpdatedMs(candidate) > effectiveUpdatedMs(matched)) {
                matched = candidate;
            }
        }
        if (matched == null) {
            return null;
        }
        return toRebuiltSyncRecord(item, matched);
    }

    private static SyncRecordDto toRebuiltSyncRecord(VerifySyncItemDto item, CloudFile matched) {
        long modifiedAt = item.getCapturedAt() != null && item.getCapturedAt() > 0
                ? item.getCapturedAt()
                : TimeUtil.toEpochMs(matched.getCapturedAt());
        return SyncRecordDto.builder()
                .localUri(item.getLocalUri())
                .mediaId(item.getMediaId())
                .fileName(item.getFileName() != null ? item.getFileName() : matched.getFileName())
                .fileSize(matched.getFileSize())
                .fileHash(matched.getSha256())
                .mediaType(item.getMediaType() != null ? item.getMediaType() : matched.getFileType())
                .modifiedAt(modifiedAt)
                .remotePath(matched.getRelativePath())
                .remoteFileId(matched.getId())
                .share(matched.getShare())
                .build();
    }

    private static boolean capturedAtMatches(long clientCapturedAtMs, String serverCapturedAtIso) {
        long serverMs = TimeUtil.toEpochMs(serverCapturedAtIso);
        if (serverMs <= 0) {
            return false;
        }
        return Math.abs(serverMs - clientCapturedAtMs) <= CAPTURED_AT_TOLERANCE_MS;
    }

    /** 返回用户文件同步索引；since&gt;0 为按 updatedAt 增量，否则分页 bootstrap */
    public SyncIndexPageDto listSyncIndex(User user, Long since, String cursor, int limit) {
        long serverTime = System.currentTimeMillis();
        if (since != null && since > 0) {
            return listSyncIndexIncremental(user, since, serverTime);
        }
        return listSyncIndexBootstrap(user, cursor, limit, serverTime);
    }

    private SyncIndexPageDto listSyncIndexBootstrap(User user, String cursor, int limit, long serverTime) {
        int pageSize = Math.min(Math.max(limit, 1), 500);
        LambdaQueryWrapper<CloudFile> wrapper = new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, user.getId())
                .isNull(CloudFile::getDeletedAt)
                .select(CloudFile::getRelativePath, CloudFile::getSha256, CloudFile::getFileSize,
                        CloudFile::getFileType, CloudFile::getThumbnailPath, CloudFile::getShare,
                        CloudFile::getCapturedAt, CloudFile::getUpdatedAt)
                .orderByAsc(CloudFile::getRelativePath)
                .last("LIMIT " + pageSize);
        if (cursor != null && !cursor.isBlank()) {
            wrapper.gt(CloudFile::getRelativePath, cursor.trim());
        }
        List<CloudFile> files = cloudFileMapper.selectList(wrapper);
        boolean hasMore = files.size() >= pageSize;
        String nextCursor = hasMore && !files.isEmpty()
                ? files.get(files.size() - 1).getRelativePath()
                : null;
        return SyncIndexPageDto.builder()
                .items(toSyncIndexItems(user, files))
                .removedPaths(List.of())
                .serverTime(serverTime)
                .fullSnapshot(true)
                .hasMore(hasMore)
                .nextCursor(nextCursor)
                .build();
    }

    private SyncIndexPageDto listSyncIndexIncremental(User user, long since, long serverTime) {
        String sinceIso = TimeUtil.fromEpochMs(since);
        List<CloudFile> changed = cloudFileMapper.selectList(new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, user.getId())
                .and(w -> w
                        .nested(n -> n.isNull(CloudFile::getDeletedAt)
                                .and(n2 -> n2.isNull(CloudFile::getUpdatedAt)
                                        .gt(CloudFile::getCreatedAt, sinceIso)
                                        .or()
                                        .gt(CloudFile::getUpdatedAt, sinceIso)))
                        .or()
                        .nested(n -> n.isNotNull(CloudFile::getDeletedAt)
                                .gt(CloudFile::getDeletedAt, sinceIso))));

        List<SyncIndexItemDto> items = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (CloudFile file : changed) {
            if (file.getDeletedAt() != null) {
                removed.add(file.getRelativePath());
            } else if (isActiveFileOnDisk(user, file)) {
                items.add(toSyncIndexItem(file));
            } else if (file.getRelativePath() != null) {
                removed.add(file.getRelativePath());
            }
        }
        return SyncIndexPageDto.builder()
                .items(items)
                .removedPaths(removed)
                .serverTime(serverTime)
                .fullSnapshot(false)
                .hasMore(false)
                .nextCursor(null)
                .build();
    }

    private List<SyncIndexItemDto> toSyncIndexItems(User user, List<CloudFile> files) {
        List<SyncIndexItemDto> result = new ArrayList<>(files.size());
        for (CloudFile file : files) {
            if (!isActiveFileOnDisk(user, file)) {
                continue;
            }
            result.add(toSyncIndexItem(file));
        }
        return result;
    }

    /** 未删除记录在磁盘上是否仍存在（排除 DB 有、盘无的幽灵项） */
    private boolean isActiveFileOnDisk(User owner, CloudFile file) {
        if (owner == null || file == null || file.getRelativePath() == null) {
            return false;
        }
        if (file.getDeletedAt() != null) {
            return false;
        }
        Path normalPath = PathUtil.resolveUserFile(
                configService.getStorageRoot(), owner.getUsername(),
                PathUtil.normalizeRelative(file.getRelativePath()));
        return Files.isRegularFile(normalPath);
    }

    private SyncIndexItemDto toSyncIndexItem(CloudFile file) {
        return SyncIndexItemDto.builder()
                .relativePath(file.getRelativePath())
                .sha256(file.getSha256())
                .fileSize(file.getFileSize())
                .fileType(file.getFileType())
                .thumbnailPath(file.getThumbnailPath())
                .share(file.getShare())
                .capturedAt(TimeUtil.toEpochMs(file.getCapturedAt()))
                .updatedAt(effectiveUpdatedMs(file))
                .build();
    }

    private static long effectiveUpdatedMs(CloudFile file) {
        long updated = TimeUtil.toEpochMs(file.getUpdatedAt());
        if (updated > 0) {
            return updated;
        }
        return TimeUtil.toEpochMs(file.getCreatedAt());
    }

    /** 按 SHA-256 查找已存在文件，用于上传去重（秒传） */
    public Optional<CloudFile> findByHash(User user, String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return Optional.empty();
        }
        CloudFile file = cloudFileMapper.selectOne(new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, user.getId())
                .eq(CloudFile::getSha256, sha256)
                .isNull(CloudFile::getDeletedAt));
        return Optional.ofNullable(file);
    }

    private Path resolveThumbnailPath(User owner, CloudFile cloudFile) {
        if (cloudFile.getThumbnailPath() == null || cloudFile.getThumbnailPath().isBlank()) {
            return null;
        }
        String thumbRel = cloudFile.getThumbnailPath();
        Path thumbPath = configService.getStorageRoot().resolve(thumbRel);
        if (Files.isRegularFile(thumbPath)) {
            return thumbPath;
        }
        if (cloudFile.getDeletedAt() != null) {
            Path trashThumb = trashFilePath(owner.getUsername(), thumbRel);
            if (Files.isRegularFile(trashThumb)) {
                return trashThumb;
            }
        }
        return null;
    }

    /** 解析文件在磁盘上的实际路径（含回收站 .trash 目录） */
    private Path resolvePhysicalFilePath(User owner, CloudFile cloudFile) {
        String normalized = PathUtil.normalizeRelative(cloudFile.getRelativePath());
        if (cloudFile.getDeletedAt() != null) {
            Path trashPath = trashFilePath(owner.getUsername(), normalized);
            if (Files.isRegularFile(trashPath)) {
                return trashPath;
            }
        }
        Path normalPath = PathUtil.resolveUserFile(
                configService.getStorageRoot(), owner.getUsername(), normalized);
        if (Files.isRegularFile(normalPath)) {
            return normalPath;
        }
        return null;
    }

    private CloudFile findByUserAndPath(Long userId, String relativePath) {
        return cloudFileMapper.selectOne(new LambdaQueryWrapper<CloudFile>()
                .eq(CloudFile::getUserId, userId)
                .eq(CloudFile::getRelativePath, relativePath)
                .isNull(CloudFile::getDeletedAt));
    }

    private AccessibleFile resolveAccessibleFile(User viewer, Long ownerUserId, String relativePath) {
        String normalized = PathUtil.normalizeRelative(relativePath);
        User owner;
        Long ownerId;

        if (ownerUserId == null || ownerUserId.equals(viewer.getId())) {
            owner = viewer;
            ownerId = viewer.getId();
        } else {
            owner = userMapper.selectById(ownerUserId);
            if (owner == null || owner.getEnabled() == null || owner.getEnabled() != 1) {
                throw new IllegalArgumentException("文件所有者不存在或已禁用");
            }
            ownerId = ownerUserId;
        }

        CloudFile file = findByUserAndPath(ownerId, normalized);
        if (file == null && ownerId.equals(viewer.getId())) {
            file = findIncludingDeleted(ownerId, normalized);
        }
        if (file == null && ownerId.equals(viewer.getId())) {
            file = diskIndexService.indexIfMissing(owner, normalized);
        }
        if (file == null) {
            throw new IllegalArgumentException("文件不存在");
        }
        if (!ownerId.equals(viewer.getId())) {
            if (file.getShare() == null || file.getShare() != 1) {
                throw new IllegalArgumentException("无权访问该文件");
            }
        }
        return new AccessibleFile(normalized, owner, file);
    }

    private Map<Long, User> loadEnabledOwners(List<CloudFile> files) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (CloudFile file : files) {
            userIds.add(file.getUserId());
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getId, userIds)
                .eq(User::getEnabled, 1));
        Map<Long, User> map = new HashMap<>();
        for (User user : users) {
            map.put(user.getId(), user);
        }
        return map;
    }

    private AlbumPhotoItemDto toAlbumItem(CloudFile file, User owner) {
        return AlbumPhotoItemDto.builder()
                .fileId(file.getId())
                .relativePath(file.getRelativePath())
                .sha256(file.getSha256())
                .fileSize(file.getFileSize())
                .thumbnailPath(file.getThumbnailPath())
                .capturedAt(TimeUtil.toEpochMs(file.getCapturedAt()))
                .share(file.getShare())
                .ownerUserId(owner.getId())
                .ownerUsername(owner.getUsername())
                .ownerDisplayName(owner.getDisplayName() != null ? owner.getDisplayName() : owner.getUsername())
                .build();
    }

    private boolean isShareableMedia(CloudFile file) {
        if (file == null) {
            return false;
        }
        if (SHAREABLE_TYPES.contains(file.getFileType())) {
            return true;
        }
        String path = file.getRelativePath();
        return path != null && (path.startsWith("audio/") || path.startsWith("uploads/audio/"));
    }

    private String normalizeShareableType(String fileType) {
        return resolveMediaCategory(fileType);
    }

    private String resolveMediaCategory(String fileType) {
        String type = fileType != null && !fileType.isBlank() ? fileType.toUpperCase() : "PHOTO";
        if ("IMAGE".equals(type)) {
            type = "PHOTO";
        }
        if (!List.of("PHOTO", "VIDEO", "AUDIO", "DOCUMENT").contains(type)) {
            throw new IllegalArgumentException("fileType 仅支持 PHOTO、VIDEO、AUDIO、DOCUMENT");
        }
        return type;
    }

    private void applySharedCategoryFilter(LambdaQueryWrapper<CloudFile> wrapper, String category) {
        applyMediaCategoryFilter(wrapper, category);
    }

    private void applyMediaCategoryFilter(LambdaQueryWrapper<CloudFile> wrapper, String category) {
        switch (category) {
            case "PHOTO" -> wrapper.eq(CloudFile::getFileType, "PHOTO")
                    .likeRight(CloudFile::getRelativePath, "photos/");
            case "VIDEO" -> wrapper.eq(CloudFile::getFileType, "VIDEO")
                    .likeRight(CloudFile::getRelativePath, "videos/");
            case "AUDIO" -> wrapper.and(w -> w.likeRight(CloudFile::getRelativePath, "audio/")
                    .or()
                    .likeRight(CloudFile::getRelativePath, "uploads/audio/"));
            case "DOCUMENT" -> wrapper.eq(CloudFile::getFileType, "DOCUMENT")
                    .likeRight(CloudFile::getRelativePath, "uploads/documents/");
            default -> wrapper.eq(CloudFile::getFileType, "PHOTO")
                    .likeRight(CloudFile::getRelativePath, "photos/");
        }
    }

    private record AccessibleFile(String normalizedPath, User owner, CloudFile file) {
    }

    private String detectFileType(String mimeType, String fileName) {
        if (mimeType != null) {
            if (mimeType.startsWith("image/")) return "PHOTO";
            if (mimeType.startsWith("video/")) return "VIDEO";
            if (mimeType.startsWith("audio/")) return "AUDIO";
        }
        String ext = FilenameUtils.getExtension(fileName).toLowerCase();
        if (List.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif").contains(ext)) return "PHOTO";
        if (List.of("mp4", "avi", "mov", "mkv", "3gp").contains(ext)) return "VIDEO";
        if (List.of("mp3", "flac", "wav", "aac", "m4a", "ogg", "wma").contains(ext)) return "AUDIO";
        if (DocumentTypes.isDocumentExtension(fileName)) return "DOCUMENT";
        return "OTHER";
    }

    private boolean isDirectChild(String parentPath, String relativePath) {
        String remainder = parentPath.isEmpty()
                ? relativePath
                : relativePath.substring(parentPath.length() + 1);
        return !remainder.contains("/");
    }

    private String extractImmediateChildDir(String parentPath, String relativePath) {
        if (!relativePath.startsWith(parentPath.isEmpty() ? "" : parentPath + "/")) {
            return null;
        }
        String remainder = parentPath.isEmpty()
                ? relativePath
                : relativePath.substring(parentPath.length() + 1);
        if (remainder.contains("/")) {
            return remainder.substring(0, remainder.indexOf('/'));
        }
        return null;
    }
}
