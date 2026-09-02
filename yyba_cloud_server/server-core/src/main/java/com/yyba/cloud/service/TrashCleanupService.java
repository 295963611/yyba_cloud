package com.yyba.cloud.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 定时清理超过保留期的回收站文件。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrashCleanupService {

    private final FileService fileService;

    @Scheduled(cron = "0 30 3 * * ?")
    public void purgeExpiredTrash() {
        try {
            int purged = fileService.purgeExpiredTrash();
            if (purged > 0) {
                log.info("回收站清理完成，永久删除 {} 个文件", purged);
            }
        } catch (Exception e) {
            log.warn("回收站清理失败: {}", e.getMessage());
        }
    }
}
