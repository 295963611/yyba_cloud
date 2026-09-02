package com.yyba.cloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyba.cloud.config.SqliteWriteGuard;
import com.yyba.cloud.entity.BackupTask;
import com.yyba.cloud.entity.CloudFile;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.mapper.BackupTaskMapper;
import com.yyba.cloud.mapper.CloudFileMapper;
import com.yyba.cloud.util.PathUtil;
import com.yyba.cloud.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 灾备业务服务。
 * <p>上传完成后将文件复制到备份目录；管理 {@code backup_tasks} 表及 files.backup_status 字段。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private static final int MAX_RETRY = 5;

    private final BackupTaskMapper backupTaskMapper;
    private final CloudFileMapper cloudFileMapper;
    private final ConfigService configService;
    private final SqliteWriteGuard sqliteWriteGuard;

    /** 上传完成后创建灾备任务（若未启用灾备则标记 SKIPPED） */
    public void scheduleBackup(CloudFile file, User user) {
        if (!configService.getBoolean(ConfigService.KEY_BACKUP_ENABLED)) {
            sqliteWriteGuard.write(() -> {
                file.setBackupStatus("SKIPPED");
                file.setUpdatedAt(TimeUtil.now());
                cloudFileMapper.updateById(file);
            });
            return;
        }

        Path source = PathUtil.resolveUserFile(
                configService.getStorageRoot(), user.getUsername(), file.getRelativePath());
        Path target = PathUtil.resolveUserFile(
                configService.getBackupRoot(), user.getUsername(), file.getRelativePath());

        BackupTask task = new BackupTask();
        task.setFileId(file.getId());
        task.setUserId(user.getId());
        task.setSourcePath(source.toString());
        task.setTargetPath(target.toString());
        task.setStatus("PENDING");
        task.setCreatedAt(TimeUtil.now());
        sqliteWriteGuard.write(() -> backupTaskMapper.insert(task));
    }

    /** 定时扫描 PENDING 任务并串行复制到灾备目录 */
    @Scheduled(fixedDelay = 30000)
    public void processBackupQueue() {
        if (!configService.getBoolean(ConfigService.KEY_BACKUP_ENABLED)) {
            return;
        }
        List<BackupTask> tasks = backupTaskMapper.selectList(new LambdaQueryWrapper<BackupTask>()
                .and(w -> w.eq(BackupTask::getStatus, "PENDING")
                        .or(inner -> inner.eq(BackupTask::getStatus, "FAILED")
                                .lt(BackupTask::getRetryCount, MAX_RETRY)))
                .orderByAsc(BackupTask::getCreatedAt));
        for (BackupTask task : tasks) {
            executeBackup(task);
        }
    }

    private void executeBackup(BackupTask task) {
        sqliteWriteGuard.write(() -> {
            task.setStatus("RUNNING");
            backupTaskMapper.updateById(task);
        });
        try {
            Path source = Path.of(task.getSourcePath());
            Path target = Path.of(task.getTargetPath());
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

            sqliteWriteGuard.write(() -> {
                task.setStatus("SUCCESS");
                task.setCompletedAt(TimeUtil.now());
                backupTaskMapper.updateById(task);

                CloudFile file = cloudFileMapper.selectById(task.getFileId());
                if (file != null) {
                    file.setBackupStatus("SYNCED");
                    file.setBackupAt(TimeUtil.now());
                    file.setUpdatedAt(TimeUtil.now());
                    cloudFileMapper.updateById(file);
                }
            });
        } catch (Exception e) {
            log.error("灾备失败 fileId={} retry={}", task.getFileId(), task.getRetryCount(), e);
            int nextRetry = task.getRetryCount() + 1;
            sqliteWriteGuard.write(() -> {
                if (nextRetry < MAX_RETRY) {
                    task.setStatus("PENDING");
                    task.setRetryCount(nextRetry);
                    task.setErrorMessage(e.getMessage());
                    backupTaskMapper.updateById(task);
                    CloudFile file = cloudFileMapper.selectById(task.getFileId());
                    if (file != null) {
                        file.setBackupStatus("PENDING");
                        file.setUpdatedAt(TimeUtil.now());
                        cloudFileMapper.updateById(file);
                    }
                } else {
                    task.setStatus("FAILED");
                    task.setRetryCount(nextRetry);
                    task.setErrorMessage(e.getMessage());
                    backupTaskMapper.updateById(task);

                    CloudFile file = cloudFileMapper.selectById(task.getFileId());
                    if (file != null) {
                        file.setBackupStatus("FAILED");
                        file.setUpdatedAt(TimeUtil.now());
                        cloudFileMapper.updateById(file);
                    }
                }
            });
        }
    }
}
