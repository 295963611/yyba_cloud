package com.yyba.cloud.service;

import com.yyba.cloud.dto.ConfigUpdateRequest;
import com.yyba.cloud.entity.AppConfig;
import com.yyba.cloud.mapper.AppConfigMapper;
import com.yyba.cloud.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 应用配置业务服务。
 * <p>读写 {@code app_config} 表，提供存储路径、灾备开关、上传分片大小等运行时参数。</p>
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    public static final String KEY_PORT = "server.port";
    public static final String KEY_STORAGE_ROOT = "storage.root_path";
    public static final String KEY_BACKUP_ENABLED = "backup.enabled";
    public static final String KEY_BACKUP_PATH = "backup.path";
    public static final String KEY_CHUNK_SIZE = "upload.chunk_size";
    public static final String KEY_TRASH_RETENTION_DAYS = "trash.retention_days";
    /** 默认分片 20MB */
    public static final int DEFAULT_CHUNK_SIZE = 20 * 1024 * 1024;
    public static final String KEY_SESSION_EXPIRE = "upload.session_expire_hours";

    private final AppConfigMapper appConfigMapper;

    public String get(String key) {
        AppConfig config = appConfigMapper.selectById(key);
        return config != null ? config.getConfigValue() : "";
    }

    public int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    public boolean getBoolean(String key) {
        return "true".equalsIgnoreCase(get(key));
    }

    public boolean isStorageConfigured() {
        String path = get(KEY_STORAGE_ROOT);
        return path != null && !path.isBlank();
    }

    public String getStorageRootPath() {
        return get(KEY_STORAGE_ROOT);
    }

    /** 获取主存储根目录路径，未配置时抛异常 */
    public Path getStorageRoot() {
        String path = get(KEY_STORAGE_ROOT);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("请先配置主存储目录");
        }
        return Path.of(path);
    }

    public Path getBackupRoot() {
        String path = get(KEY_BACKUP_PATH);
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("请先配置灾备目录");
        }
        return Path.of(path);
    }

    public int getChunkSize() {
        return getInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE);
    }

    public int getSessionExpireHours() {
        return getInt(KEY_SESSION_EXPIRE, 24);
    }

    public int getTrashRetentionDays() {
        return getInt(KEY_TRASH_RETENTION_DAYS, 30);
    }

    public Map<String, String> getAll() {
        Map<String, String> map = new HashMap<>();
        appConfigMapper.selectList(null).forEach(c -> map.put(c.getConfigKey(), c.getConfigValue()));
        return map;
    }

    /** 批量更新启动器提交的配置项 */
    @Transactional
    public void update(ConfigUpdateRequest request) {
        if (request.getPort() != null) {
            save(KEY_PORT, String.valueOf(request.getPort()));
        }
        if (request.getStorageRootPath() != null) {
            validateWritableDirectory(request.getStorageRootPath(), "主存储目录");
            save(KEY_STORAGE_ROOT, request.getStorageRootPath());
        }
        if (request.getBackupEnabled() != null) {
            save(KEY_BACKUP_ENABLED, String.valueOf(request.getBackupEnabled()));
        }
        if (request.getBackupPath() != null) {
            if (!request.getBackupPath().isBlank()) {
                validateWritableDirectory(request.getBackupPath(), "灾备目录");
            }
            save(KEY_BACKUP_PATH, request.getBackupPath());
        }
        if (request.getChunkSize() != null && request.getChunkSize() > 0) {
            save(KEY_CHUNK_SIZE, String.valueOf(request.getChunkSize()));
        }
        if (request.getTrashRetentionDays() != null && request.getTrashRetentionDays() > 0) {
            save(KEY_TRASH_RETENTION_DAYS, String.valueOf(request.getTrashRetentionDays()));
        }
    }

    @Transactional
    public void save(String key, String value) {
        AppConfig config = appConfigMapper.selectById(key);
        if (config == null) {
            config = new AppConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setUpdatedAt(TimeUtil.now());
            appConfigMapper.insert(config);
        } else {
            config.setConfigValue(value);
            config.setUpdatedAt(TimeUtil.now());
            appConfigMapper.updateById(config);
        }
    }

    private void validateWritableDirectory(String path, String label) {
        try {
            Path dir = Path.of(path);
            Files.createDirectories(dir);
            if (!Files.isWritable(dir)) {
                throw new IllegalArgumentException(label + "不可写: " + path);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(label + "无效: " + path);
        }
    }
}
