package com.yyba.cloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyba.cloud.config.SqliteWriteGuard;
import com.yyba.cloud.dto.*;
import com.yyba.cloud.entity.CloudFile;
import com.yyba.cloud.entity.UploadSession;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.mapper.UploadSessionMapper;
import com.yyba.cloud.util.PathUtil;
import com.yyba.cloud.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;

/**
 * 分片上传业务服务。
 * <p>管理 {@code upload_sessions} 表：初始化会话、接收分片、合并文件并写入 files 表。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {

    private final UploadSessionMapper uploadSessionMapper;
    private final ConfigService configService;
    private final FileService fileService;
    private final BackupService backupService;
    private final OperationLogService operationLogService;
    private final ThumbnailAsyncService thumbnailAsyncService;
    private final ObjectMapper objectMapper;
    private final SqliteWriteGuard sqliteWriteGuard;
    private final TransactionTemplate transactionTemplate;

    /** 创建上传会话并分配临时分片目录 */
    public UploadInitResponse initUpload(User user, UploadInitRequest request) {
        if (request.getSha256() != null && !request.getSha256().isBlank()) {
            Optional<CloudFile> existing = fileService.findByHash(user, request.getSha256());
            if (existing.isPresent()) {
                throw new IllegalArgumentException("文件已存在: " + existing.get().getRelativePath());
            }
        }

        int chunkSize = configService.getChunkSize();
        int totalChunks = (int) Math.ceil((double) request.getTotalSize() / chunkSize);
        String uploadId = UUID.randomUUID().toString();
        String relativePath = PathUtil.normalizeRelative(request.getRelativePath());
        Path storageRoot = configService.getStorageRoot();
        Path chunkDir = storageRoot.resolve(".chunks").resolve(uploadId);
        try {
            Files.createDirectories(chunkDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建临时目录失败");
        }

        String now = TimeUtil.now();
        UploadSession session = new UploadSession();
        session.setId(uploadId);
        session.setUserId(user.getId());
        session.setFileName(request.getFileName());
        session.setRelativePath(relativePath);
        session.setTotalSize(request.getTotalSize());
        session.setChunkSize(chunkSize);
        session.setTotalChunks(totalChunks);
        session.setUploadedChunks("[]");
        session.setMimeType(request.getMimeType());
        session.setSha256(request.getSha256());
        if (request.getCapturedAt() != null && request.getCapturedAt() > 0) {
            session.setCapturedAt(TimeUtil.fromEpochMs(request.getCapturedAt()));
        }
        session.setSource(request.getSource() != null ? request.getSource() : "WEB_UPLOAD");
        session.setStatus("INIT");
        session.setTempDir(chunkDir.toString());
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(TimeUtil.plusHours(configService.getSessionExpireHours()));

        sqliteWriteGuard.write(() -> uploadSessionMapper.insert(session));

        return UploadInitResponse.builder()
                .uploadId(uploadId)
                .chunkSize(chunkSize)
                .totalChunks(totalChunks)
                .uploadedChunks(List.of())
                .build();
    }

    /** 保存单个分片并更新 uploaded_chunks 进度 */
    public void uploadChunk(User user, String uploadId, int chunkIndex, InputStream input, long contentLength) {
        UploadSession session = getSession(user, uploadId);
        if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
            throw new IllegalArgumentException("分片索引无效");
        }

        List<Integer> uploaded = readChunks(session.getUploadedChunks());
        if (uploaded.contains(chunkIndex)) {
            return;
        }

        Path chunkDir = Path.of(session.getTempDir());
        Path chunkFile = chunkDir.resolve(String.valueOf(chunkIndex));
        long expectedBytes = expectedChunkBytes(session, chunkIndex);
        try {
            Files.createDirectories(chunkDir);
            long written = input.transferTo(Files.newOutputStream(chunkFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            if (written != expectedBytes) {
                Files.deleteIfExists(chunkFile);
                throw new IllegalStateException("分片大小不匹配: 期望 " + expectedBytes + " 字节, 实际 " + written
                        + " 字节" + (contentLength >= 0 ? ", Content-Length=" + contentLength : ""));
            }
        } catch (IOException e) {
            log.error("保存分片失败 uploadId={} chunk={} dir={}", uploadId, chunkIndex, session.getTempDir(), e);
            throw new IllegalStateException("保存分片失败: " + e.getMessage());
        }

        uploaded.add(chunkIndex);
        Collections.sort(uploaded);
        if (session.getTotalChunks() != null && session.getTotalChunks() <= 1) {
            return;
        }
        String uploadedChunksJson = writeChunks(uploaded);
        sqliteWriteGuard.write(() -> updateSessionProgressInternal(session.getId(), uploadedChunksJson));
    }

    private static long expectedChunkBytes(UploadSession session, int chunkIndex) {
        int chunkSize = session.getChunkSize() != null && session.getChunkSize() > 0
                ? session.getChunkSize() : ConfigService.DEFAULT_CHUNK_SIZE;
        long totalSize = session.getTotalSize() != null ? session.getTotalSize() : 0L;
        long offset = (long) chunkIndex * chunkSize;
        return Math.min(chunkSize, Math.max(0L, totalSize - offset));
    }

    public UploadStatusResponse getStatus(User user, String uploadId) {
        UploadSession session = getSession(user, uploadId);
        return UploadStatusResponse.builder()
                .uploadId(uploadId)
                .status(session.getStatus())
                .chunkSize(session.getChunkSize())
                .totalChunks(session.getTotalChunks())
                .uploadedChunks(readChunks(session.getUploadedChunks()))
                .build();
    }

    /** 合并全部分片、校验哈希、写入 files 表并触发灾备 */
    public UploadCompleteResponse completeUpload(User user, String uploadId, String clientIp) {
        UploadSession session = getSession(user, uploadId);
        List<Integer> uploaded = resolveUploadedChunks(session);
        if (uploaded.size() != session.getTotalChunks()) {
            throw new IllegalArgumentException("分片未上传完成");
        }

        sqliteWriteGuard.write(() -> markSessionMergingInternal(session.getId()));

        Path target = PathUtil.resolveUserFile(
                configService.getStorageRoot(), user.getUsername(), session.getRelativePath());
        try {
            Files.createDirectories(target.getParent());
            mergeChunks(session, target);
            String sha256 = session.getSha256() != null ? session.getSha256() : calculateSha256(target);
            if (session.getSha256() != null && !session.getSha256().equalsIgnoreCase(sha256)) {
                Files.deleteIfExists(target);
                sqliteWriteGuard.write(() -> markSessionFailedInternal(session.getId(), "文件校验失败"));
                throw new IllegalArgumentException("文件校验失败");
            }

            UploadCompleteResponse response = sqliteWriteGuard.write(() ->
                    transactionTemplate.execute(status -> finalizeUploadInternal(user, session, sha256)));
            cleanupChunks(session);
            CloudFile file = fileService.getById(response.getFileId());
            if (file != null) {
                backupService.scheduleBackup(file, user);
            }
            operationLogService.log(user.getId(), "UPLOAD", response.getRelativePath(), clientIp, null);
            thumbnailAsyncService.generateAfterUpload(
                    user.getId(), response.getFileId(), session.getRelativePath(),
                    session.getMimeType(), session.getFileName());
            return response;
        } catch (IOException e) {
            sqliteWriteGuard.write(() -> markSessionFailedInternal(session.getId(), e.getMessage()));
            throw new IllegalStateException("合并文件失败: " + e.getMessage());
        }
    }

    /** 定时清理已过期的未完成上传会话及临时分片 */
    public void cleanupExpiredSessions() {
        List<UploadSession> expired = uploadSessionMapper.selectList(new LambdaQueryWrapper<UploadSession>()
                .ne(UploadSession::getStatus, "COMPLETED")
                .lt(UploadSession::getExpiresAt, TimeUtil.now()));
        sqliteWriteGuard.write(() -> {
            for (UploadSession session : expired) {
                session.setStatus("EXPIRED");
                uploadSessionMapper.updateById(session);
                cleanupChunks(session);
            }
        });
    }

    private void updateSessionProgressInternal(String sessionId, String uploadedChunksJson) {
        UploadSession session = uploadSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("上传会话不存在");
        }
        session.setUploadedChunks(uploadedChunksJson);
        session.setStatus("UPLOADING");
        session.setUpdatedAt(TimeUtil.now());
        uploadSessionMapper.updateById(session);
    }

    private void markSessionMergingInternal(String sessionId) {
        UploadSession session = uploadSessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("上传会话不存在");
        }
        session.setStatus("MERGING");
        session.setUpdatedAt(TimeUtil.now());
        uploadSessionMapper.updateById(session);
    }

    private void markSessionFailedInternal(String sessionId, String errorMessage) {
        UploadSession session = uploadSessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        session.setStatus("FAILED");
        session.setErrorMessage(errorMessage);
        session.setUpdatedAt(TimeUtil.now());
        uploadSessionMapper.updateById(session);
    }

    private UploadCompleteResponse finalizeUploadInternal(User user, UploadSession session, String sha256) {
        String capturedAt = session.getCapturedAt() != null ? session.getCapturedAt() : TimeUtil.now();
        CloudFile file = fileService.saveFileRecord(
                user,
                session.getRelativePath(),
                session.getFileName(),
                session.getTotalSize(),
                session.getMimeType(),
                sha256,
                session.getSource(),
                null,
                capturedAt
        );

        session.setStatus("COMPLETED");
        session.setUpdatedAt(TimeUtil.now());
        uploadSessionMapper.updateById(session);

        return UploadCompleteResponse.builder()
                .fileId(file.getId())
                .relativePath(file.getRelativePath())
                .fileSize(file.getFileSize())
                .build();
    }

    private List<Integer> resolveUploadedChunks(UploadSession session) {
        List<Integer> uploaded = readChunks(session.getUploadedChunks());
        if (uploaded.size() == session.getTotalChunks()) {
            return uploaded;
        }
        if (session.getTotalChunks() != null && session.getTotalChunks() == 1 && chunkFileExists(session, 0)) {
            return List.of(0);
        }
        return uploaded;
    }

    private boolean chunkFileExists(UploadSession session, int chunkIndex) {
        if (session.getTempDir() == null) {
            return false;
        }
        return Files.isRegularFile(Path.of(session.getTempDir()).resolve(String.valueOf(chunkIndex)));
    }

    private void mergeChunks(UploadSession session, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int i = 0; i < session.getTotalChunks(); i++) {
                Path chunkFile = Path.of(session.getTempDir()).resolve(String.valueOf(i));
                Files.copy(chunkFile, out);
            }
        }
    }

    private void cleanupChunks(UploadSession session) {
        if (session.getTempDir() != null) {
            try {
                FileUtils.deleteDirectory(Path.of(session.getTempDir()).toFile());
            } catch (IOException ignored) {
            }
        }
    }

    private String calculateSha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用");
        }
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream din = new DigestInputStream(in, digest)) {
            din.transferTo(OutputStream.nullOutputStream());
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private UploadSession getSession(User user, String uploadId) {
        UploadSession session = uploadSessionMapper.selectOne(new LambdaQueryWrapper<UploadSession>()
                .eq(UploadSession::getId, uploadId)
                .eq(UploadSession::getUserId, user.getId()));
        if (session == null) {
            throw new IllegalArgumentException("上传会话不存在");
        }
        String status = session.getStatus();
        if (status != null && ("EXPIRED".equals(status) || "FAILED".equals(status)
                || "COMPLETED".equals(status))) {
            throw new IllegalArgumentException("上传会话已失效");
        }
        return session;
    }

    private List<Integer> readChunks(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Integer>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeChunks(List<Integer> chunks) {
        try {
            return objectMapper.writeValueAsString(chunks);
        } catch (Exception e) {
            throw new IllegalStateException("序列化分片信息失败");
        }
    }
}
