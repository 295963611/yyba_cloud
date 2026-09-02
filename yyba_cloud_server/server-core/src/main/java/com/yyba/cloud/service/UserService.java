package com.yyba.cloud.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yyba.cloud.dto.LoginRequest;
import com.yyba.cloud.dto.LoginResponse;
import com.yyba.cloud.dto.RegisterRequest;
import com.yyba.cloud.entity.User;
import com.yyba.cloud.mapper.UserMapper;
import com.yyba.cloud.security.AdminGuard;
import com.yyba.cloud.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * 用户业务服务。
 * <p>负责用户注册、登录鉴权、查询；操作 {@code users} 表，并在注册时初始化用户存储目录。</p>
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ConfigService configService;
    private final OperationLogService operationLogService;
    private final TransactionTemplate transactionTemplate;
    private final DiskIndexAsyncService diskIndexAsyncService;

    /** 注册新用户（由启动器内部 API 调用） */
    public User register(RegisterRequest request) {
        validateRegisterRequest(request);
        if (existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("用户名已存在");
        }
        configService.getStorageRoot();
        User user = transactionTemplate.execute(status -> persistNewUserInternal(request, null));
        createUserDirectories(user.getUsername());
        operationLogService.log(user.getId(), "REGISTER", user.getUsername(), null, null);
        diskIndexAsyncService.reconcileUserMediaForceAsync(user.getId(), "ALL");
        return user;
    }

    private void validateRegisterRequest(RegisterRequest request) {
        if (request.getUsername() == null || request.getUsername().length() < 2) {
            throw new IllegalArgumentException("用户名至少 2 个字符");
        }
        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("密码至少 6 个字符");
        }
    }

    /** 校验用户名密码并签发 JWT */
    public LoginResponse login(LoginRequest request, String clientIp) {
        User user = findByUsername(request.getUsername());
        if (user == null) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (user.getEnabled() != 1) {
            throw new IllegalArgumentException("账号已禁用");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        String token = jwtService.generateToken(user.getId(), user.getUsername());
        operationLogService.log(user.getId(), "LOGIN", null, clientIp, null);
        return new LoginResponse(token, user.getUsername(), user.getId(), user.getRole());
    }

    public User getById(Long id) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return user;
    }

    public User getByUsername(String username) {
        User user = findByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return user;
    }

    public List<User> listAll() {
        return userMapper.selectList(null);
    }

    public boolean hasUsers() {
        return userMapper.selectCount(null) > 0;
    }

    /** 首次引导注册：仅当系统尚无用户时允许，自动成为管理员 */
    public User setupRegister(RegisterRequest request) {
        if (hasUsers()) {
            throw new IllegalArgumentException("系统已初始化，请登录或由管理员添加账号");
        }
        return register(request);
    }

    /** 管理员创建成员账号 */
    public User createByAdmin(RegisterRequest request) {
        validateRegisterRequest(request);
        if (existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("用户名已存在");
        }
        configService.getStorageRoot();
        User user = transactionTemplate.execute(status -> persistNewUserInternal(request, "MEMBER"));
        createUserDirectories(user.getUsername());
        operationLogService.log(user.getId(), "REGISTER", user.getUsername(), null, "admin-create");
        diskIndexAsyncService.reconcileUserMediaForceAsync(user.getId(), "ALL");
        return user;
    }

    /** 启用或禁用用户 */
    public User setEnabled(Long userId, boolean enabled, Long operatorId) {
        User user = transactionTemplate.execute(status -> {
            User target = getById(userId);
            if (target.getId().equals(operatorId) && !enabled) {
                throw new IllegalArgumentException("不能禁用当前登录账号");
            }
            if (!enabled && AdminGuard.isAdmin(target)) {
                long enabledAdmins = userMapper.selectCount(new LambdaQueryWrapper<User>()
                        .eq(User::getRole, AdminGuard.ROLE_ADMIN)
                        .eq(User::getEnabled, 1));
                if (enabledAdmins <= 1) {
                    throw new IllegalArgumentException("不能禁用唯一的管理员账号");
                }
            }
            target.setEnabled(enabled ? 1 : 0);
            target.setUpdatedAt(TimeUtil.now());
            userMapper.updateById(target);
            return target;
        });
        operationLogService.log(operatorId, enabled ? "ENABLE_USER" : "DISABLE_USER",
                user.getUsername(), null, null);
        return user;
    }

    /** 管理员重置用户密码 */
    public User resetPassword(Long userId, String newPassword, Long operatorId) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 个字符");
        }
        User user = transactionTemplate.execute(status -> {
            User target = getById(userId);
            target.setPasswordHash(passwordEncoder.encode(newPassword));
            target.setUpdatedAt(TimeUtil.now());
            userMapper.updateById(target);
            return target;
        });
        operationLogService.log(operatorId, "RESET_PASSWORD", user.getUsername(), null, null);
        return user;
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
    }

    private boolean existsByUsername(String username) {
        return userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)) > 0;
    }

    private User persistNewUserInternal(RegisterRequest request, String fixedRole) {
        String now = TimeUtil.now();
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName() != null ? request.getDisplayName() : request.getUsername());
        if (fixedRole != null) {
            user.setRole(fixedRole);
        } else {
            long existingUsers = userMapper.selectCount(null);
            user.setRole(existingUsers == 0 ? AdminGuard.ROLE_ADMIN : "MEMBER");
        }
        user.setStorageQuotaBytes(-1L);
        user.setEnabled(1);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }

    private void createUserDirectories(String username) {
        try {
            var root = configService.getStorageRoot();
            Files.createDirectories(root.resolve(username).resolve("photos"));
            Files.createDirectories(root.resolve(username).resolve("videos"));
            Files.createDirectories(root.resolve(username).resolve("audio"));
            Files.createDirectories(root.resolve(username).resolve("uploads"));
        } catch (IOException e) {
            throw new IllegalStateException("创建用户目录失败: " + e.getMessage());
        }
    }
}
