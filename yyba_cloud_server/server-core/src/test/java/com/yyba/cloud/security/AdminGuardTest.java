package com.yyba.cloud.security;

import com.yyba.cloud.entity.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminGuardTest {

    @Test
    void isAdmin_returnsTrueForAdminRole() {
        User user = new User();
        user.setRole("ADMIN");
        assertTrue(AdminGuard.isAdmin(user));
    }

    @Test
    void isAdmin_isCaseInsensitive() {
        User user = new User();
        user.setRole("admin");
        assertTrue(AdminGuard.isAdmin(user));
    }

    @Test
    void isAdmin_returnsFalseForMember() {
        User user = new User();
        user.setRole("MEMBER");
        assertFalse(AdminGuard.isAdmin(user));
    }

    @Test
    void isAdmin_returnsFalseForNullUser() {
        assertFalse(AdminGuard.isAdmin(null));
    }

    @Test
    void requireAdmin_throwsForMember() {
        User user = new User();
        user.setRole("MEMBER");
        assertThrows(IllegalArgumentException.class, () -> AdminGuard.requireAdmin(user));
    }

    @Test
    void requireAdmin_passesForAdmin() {
        User user = new User();
        user.setRole("ADMIN");
        assertDoesNotThrow(() -> AdminGuard.requireAdmin(user));
    }
}
