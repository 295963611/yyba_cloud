package com.yyba.cloud.util;

import org.springframework.http.MediaType;

/**
 * 根据相对路径推断 HTTP Content-Type，供网盘静态直链 inline 展示。
 */
public final class MediaTypeUtil {

    private MediaTypeUtil() {
    }

    public static MediaType probeFromPath(String relativePath) {
        if (relativePath == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String lower = relativePath.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".bmp")) return MediaType.parseMediaType("image/bmp");
        if (lower.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
        if (lower.endsWith(".mp4")) return MediaType.parseMediaType("video/mp4");
        if (lower.endsWith(".webm")) return MediaType.parseMediaType("video/webm");
        if (lower.endsWith(".mov")) return MediaType.parseMediaType("video/quicktime");
        if (lower.endsWith(".mkv")) return MediaType.parseMediaType("video/x-matroska");
        if (lower.endsWith(".mp3")) return MediaType.parseMediaType("audio/mpeg");
        if (lower.endsWith(".m4a")) return MediaType.parseMediaType("audio/mp4");
        if (lower.endsWith(".wav")) return MediaType.parseMediaType("audio/wav");
        if (lower.endsWith(".ogg")) return MediaType.parseMediaType("audio/ogg");
        if (lower.endsWith(".flac")) return MediaType.parseMediaType("audio/flac");
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".txt")) return MediaType.TEXT_PLAIN;
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return MediaType.TEXT_HTML;
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    public static String extractFileName(String path) {
        if (path == null || path.isBlank()) {
            return "file";
        }
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** 直传磁盘文件 probeContentType 为空时的 MIME 回退 */
    public static String guessMimeFromPath(String relativePath) {
        return probeFromPath(relativePath).toString();
    }
}
