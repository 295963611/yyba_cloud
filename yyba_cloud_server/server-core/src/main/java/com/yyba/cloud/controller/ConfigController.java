package com.yyba.cloud.controller;

import com.yyba.cloud.dto.ApiResponse;
import com.yyba.cloud.dto.ConfigUpdateRequest;
import com.yyba.cloud.security.AdminGuard;
import com.yyba.cloud.security.UserPrincipal;
import com.yyba.cloud.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Web 管理端读取/更新网盘运行配置（存储目录、灾备、分片大小等）。
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    @GetMapping
    public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal UserPrincipal principal) {
        Map<String, String> all = configService.getAll();
        Map<String, Object> data = new HashMap<>();
        data.put("port", configService.getInt(ConfigService.KEY_PORT, 8080));
        data.put("storageRootPath", all.getOrDefault(ConfigService.KEY_STORAGE_ROOT, ""));
        data.put("backupEnabled", configService.getBoolean(ConfigService.KEY_BACKUP_ENABLED));
        data.put("backupPath", all.getOrDefault(ConfigService.KEY_BACKUP_PATH, ""));
        data.put("chunkSize", configService.getChunkSize());
        data.put("trashRetentionDays", configService.getTrashRetentionDays());
        data.put("admin", AdminGuard.isAdmin(principal.getUser()));
        return ApiResponse.ok(data);
    }

    @PutMapping
    public ApiResponse<Void> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ConfigUpdateRequest request) {
        AdminGuard.requireAdmin(principal.getUser());
        configService.update(request);
        return ApiResponse.ok("配置已保存", null);
    }
}
