package com.yyba.cloud.util;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathUtil {

    private PathUtil() {
    }

    public static String normalizeRelative(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("路径不允许包含 ..");
        }
        return normalized;
    }

    public static Path userRoot(Path storageRoot, String username) {
        return storageRoot.resolve(username);
    }

    public static Path resolveUserFile(Path storageRoot, String username, String relativePath) {
        Path base = userRoot(storageRoot, username).toAbsolutePath().normalize();
        Path target = base.resolve(normalizeRelative(relativePath)).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        return target;
    }
}
