package com.yyba.cloud.service;

import com.yyba.cloud.entity.User;
import com.yyba.cloud.mapper.UserMapper;
import com.yyba.cloud.util.PathUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 上传完成后异步生成缩略图并回写 files 表，避免阻塞 SQLite 写锁。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThumbnailAsyncService {

    private final ThumbnailService thumbnailService;
    private final ConfigService configService;
    private final FileService fileService;
    private final UserMapper userMapper;

    @Async
    public void generateAfterUpload(Long userId, Long fileId, String relativePath,
                                    String mimeType, String fileName) {
        if (userId == null || fileId == null || relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            User user = userMapper.selectById(userId);
            if (user == null) {
                return;
            }
            Path target = PathUtil.resolveUserFile(
                    configService.getStorageRoot(), user.getUsername(), relativePath);
            if (!Files.isRegularFile(target)) {
                return;
            }
            String thumbRelative = null;
            if (thumbnailService.isPhoto(mimeType, fileName)) {
                thumbRelative = thumbnailService.generatePhotoThumbnail(
                        target, user, relativePath, mimeType, fileName);
            } else if (thumbnailService.isVideo(mimeType, fileName)) {
                thumbRelative = thumbnailService.generateVideoThumbnail(
                        target, user, relativePath, mimeType, fileName);
            }
            if (thumbRelative != null) {
                fileService.updateThumbnailPath(fileId, thumbRelative);
            }
        } catch (Exception e) {
            log.warn("异步缩略图失败 fileId={} path={}: {}", fileId, relativePath, e.getMessage());
        }
    }
}
