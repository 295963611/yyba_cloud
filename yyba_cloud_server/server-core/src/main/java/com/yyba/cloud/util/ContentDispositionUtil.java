package com.yyba.cloud.util;

import org.springframework.http.ContentDisposition;

import java.nio.charset.StandardCharsets;

/**
 * 生成 Tomcat 可接受的 Content-Disposition（支持中文等非 ASCII 文件名）。
 */
public final class ContentDispositionUtil {

    private ContentDispositionUtil() {
    }

    public static String attachment(String fileName) {
        return disposition("attachment", fileName);
    }

    public static String inline(String fileName) {
        return disposition("inline", fileName);
    }

    private static String disposition(String type, String fileName) {
        String safeName = fileName == null || fileName.isBlank() ? "download" : fileName;
        return ContentDisposition.builder(type)
                .filename(safeName, StandardCharsets.UTF_8)
                .build()
                .toString();
    }
}
