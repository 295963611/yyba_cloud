package com.yyba.cloud.controller;

import com.yyba.cloud.dto.ApiResponse;
import com.yyba.cloud.dto.ConfigUpdateRequest;
import com.yyba.cloud.dto.LoginRequest;
import com.yyba.cloud.dto.LoginResponse;
import com.yyba.cloud.dto.RegisterRequest;
import com.yyba.cloud.dto.SetupRequest;
import com.yyba.cloud.dto.SetupBrowseResponse;
import com.yyba.cloud.dto.SetupStatusResponse;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.service.ConfigService;
import com.yyba.cloud.service.SetupBrowseService;
import com.yyba.cloud.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口控制器。
 * <p>面向 Android / Web 客户端，提供用户登录并颁发 JWT。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final ConfigService configService;
    private final SetupBrowseService setupBrowseService;

    /**
     * 用户登录。
     *
     * @param request 用户名与密码
     * @return JWT Token 及用户基本信息
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        return ApiResponse.ok(userService.login(request, ip));
    }

    /** 查询系统是否已有用户（用于 Web 引导页路由） */
    @GetMapping("/setup-status")
    public ApiResponse<SetupStatusResponse> setupStatus() {
        return ApiResponse.ok(new SetupStatusResponse(
                userService.hasUsers(),
                configService.isStorageConfigured(),
                configService.getStorageRootPath()
        ));
    }

    /** 引导阶段浏览服务器目录（仅未初始化时可用） */
    @GetMapping("/setup/browse")
    public ApiResponse<SetupBrowseResponse> browseSetup(@RequestParam(required = false) String path) {
        if (userService.hasUsers()) {
            throw new IllegalArgumentException("系统已初始化");
        }
        return ApiResponse.ok(setupBrowseService.browse(path));
    }

    /** 首次引导：配置主存储目录并创建第一个管理员账号 */
    @PostMapping("/setup")
    public ApiResponse<LoginResponse> setup(@Valid @RequestBody SetupRequest request,
                                            HttpServletRequest httpRequest) {
        if (userService.hasUsers()) {
            throw new IllegalArgumentException("系统已初始化，请登录或由管理员添加账号");
        }
        ConfigUpdateRequest configRequest = new ConfigUpdateRequest();
        configRequest.setStorageRootPath(request.getStorageRootPath());
        configService.update(configRequest);

        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setUsername(request.getUsername());
        registerRequest.setPassword(request.getPassword());
        registerRequest.setDisplayName(request.getDisplayName());
        User user = userService.setupRegister(registerRequest);
        String ip = httpRequest.getRemoteAddr();
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(user.getUsername());
        loginRequest.setPassword(request.getPassword());
        LoginResponse response = userService.login(loginRequest, ip);
        return ApiResponse.ok("管理员账号创建成功", response);
    }
}
