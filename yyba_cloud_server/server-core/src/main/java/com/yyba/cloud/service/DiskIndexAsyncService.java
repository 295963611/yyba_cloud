package com.yyba.cloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyba.cloud.entity.UploadSession;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.mapper.UploadSessionMapper;
import com.yyba.cloud.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 后台异步执行磁盘索引，避免上百张存量照片同步入库阻塞 HTTP 请求。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiskIndexAsyncService {

    private static final List<String> ACTIVE_UPLOAD_STATUSES = List.of("INIT", "UPLOADING", "MERGING");

    private final DiskIndexService diskIndexService;
    private final UserMapper userMapper;
    private final UploadSessionMapper uploadSessionMapper;

    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    @Async
    public void reconcileUserMediaAsync(Long userId, String category) {
        runAsync(userId, category, false);
    }

    @Async
    public void reconcileUserMediaForceAsync(Long userId, String category) {
        runAsync(userId, category, true);
    }

    private void runAsync(Long userId, String category, boolean force) {
        String key = userId + ":" + (category == null || category.isBlank() ? "ALL" : category.toUpperCase());
        if (!inProgress.add(key)) {
            log.debug("磁盘索引已在进行 userId={} category={}", userId, category);
            return;
        }
        try {
            if (!force && hasActiveUploads(userId)) {
                log.debug("跳过磁盘索引：用户上传进行中 userId={} category={}", userId, category);
                return;
            }
            User user = userMapper.selectById(userId);
            if (user == null || user.getEnabled() != 1) {
                return;
            }
            int indexed = force
                    ? diskIndexService.reconcileUserMediaForce(user, category)
                    : diskIndexService.reconcileUserMedia(user, category);
            if (indexed > 0) {
                log.info("后台磁盘索引完成 user={} category={} count={}", user.getUsername(), category, indexed);
            }
        } catch (Exception e) {
            log.warn("后台磁盘索引失败 userId={} category={}: {}", userId, category, e.getMessage());
        } finally {
            inProgress.remove(key);
        }
    }

    private boolean hasActiveUploads(Long userId) {
        if (userId == null) {
            return false;
        }
        Long count = uploadSessionMapper.selectCount(new LambdaQueryWrapper<UploadSession>()
                .eq(UploadSession::getUserId, userId)
                .in(UploadSession::getStatus, ACTIVE_UPLOAD_STATUSES));
        return count != null && count > 0;
    }
}
