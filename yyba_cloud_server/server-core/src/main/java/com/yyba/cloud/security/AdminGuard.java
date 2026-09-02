package com.yyba.cloud.security;

import com.yyba.cloud.entity.User;

public final class AdminGuard {

    public static final String ROLE_ADMIN = "ADMIN";

    private AdminGuard() {
    }

    public static boolean isAdmin(User user) {
        return user != null && ROLE_ADMIN.equalsIgnoreCase(user.getRole());
    }

    public static void requireAdmin(User user) {
        if (!isAdmin(user)) {
            throw new IllegalArgumentException("需要管理员权限");
        }
    }
}
