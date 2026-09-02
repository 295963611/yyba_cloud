package com.yyba.cloud.controller;

import com.yyba.cloud.dto.ApiResponse;
import com.yyba.cloud.dto.RegisterRequest;
import com.yyba.cloud.dto.ResetPasswordRequest;
import com.yyba.cloud.dto.UserSummary;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.security.AdminGuard;
import com.yyba.cloud.security.UserPrincipal;
import com.yyba.cloud.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Web 管理端用户管理接口（仅管理员）。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    public ApiResponse<List<UserSummary>> list(@AuthenticationPrincipal UserPrincipal principal) {
        AdminGuard.requireAdmin(principal.getUser());
        List<UserSummary> users = userService.listAll().stream()
                .map(this::toSummary)
                .toList();
        return ApiResponse.ok(users);
    }

    @PostMapping
    public ApiResponse<UserSummary> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody RegisterRequest request) {
        AdminGuard.requireAdmin(principal.getUser());
        User user = userService.createByAdmin(request);
        return ApiResponse.ok("账号创建成功", toSummary(user));
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<UserSummary> setEnabled(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {
        AdminGuard.requireAdmin(principal.getUser());
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            throw new IllegalArgumentException("缺少 enabled 参数");
        }
        User user = userService.setEnabled(id, enabled, principal.getUser().getId());
        return ApiResponse.ok(enabled ? "账号已启用" : "账号已禁用", toSummary(user));
    }

    @PutMapping("/{id}/password")
    public ApiResponse<UserSummary> resetPassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody ResetPasswordRequest request) {
        AdminGuard.requireAdmin(principal.getUser());
        User user = userService.resetPassword(id, request.getPassword(), principal.getUser().getId());
        return ApiResponse.ok("密码已重置", toSummary(user));
    }

    private UserSummary toSummary(User user) {
        return new UserSummary(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.getEnabled(),
                user.getCreatedAt()
        );
    }
}
