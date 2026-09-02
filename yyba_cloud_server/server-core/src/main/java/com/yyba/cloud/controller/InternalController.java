package com.yyba.cloud.controller;

import com.yyba.cloud.dto.ApiResponse;
import com.yyba.cloud.dto.ConfigUpdateRequest;
import com.yyba.cloud.dto.RegisterRequest;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.service.ConfigService;
import com.yyba.cloud.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内部管理接口控制器。
 * <p>仅供 JavaFX 启动器调用，需携带 X-Internal-Key；用于注册用户、读写 app_config、列出用户。</p>
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class InternalController {

    private final UserService userService;
    private final ConfigService configService;

    @Value("${yyba.internal-api-key}")
    private String internalApiKey;

    /**
     * 注册新用户并创建其存储目录。
     */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(
            @RequestHeader(value = "X-Internal-Key", required = false) String key,
            @Valid @RequestBody RegisterRequest request) {
        checkKey(key);
        User user = userService.register(request);
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        return ApiResponse.ok("注册成功", data);
    }

    /**
     * 读取全部应用配置（app_config 表）。
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, String>> getConfig(
            @RequestHeader(value = "X-Internal-Key", required = false) String key) {
        checkKey(key);
        return ApiResponse.ok(configService.getAll());
    }

    /**
     * 更新应用配置（存储路径、灾备、分片大小等）。
     */
    @PutMapping("/config")
    public ApiResponse<Void> updateConfig(
            @RequestHeader(value = "X-Internal-Key", required = false) String key,
            @RequestBody ConfigUpdateRequest request) {
        checkKey(key);
        configService.update(request);
        return ApiResponse.ok("配置已保存", null);
    }

    /**
     * 列出所有注册用户。
     */
    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> listUsers(
            @RequestHeader(value = "X-Internal-Key", required = false) String key) {
        checkKey(key);
        List<Map<String, Object>> users = userService.listAll().stream().map(u -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName());
            m.put("enabled", u.getEnabled());
            m.put("createdAt", u.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.ok(users);
    }

    private void checkKey(String key) {
        if (key == null || !key.equals(internalApiKey)) {
            throw new IllegalArgumentException("内部 API 密钥无效");
        }
    }
}
