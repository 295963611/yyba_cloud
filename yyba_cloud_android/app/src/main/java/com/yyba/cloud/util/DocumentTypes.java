package com.yyba.cloud.util;

import java.util.List;
import java.util.Locale;

/** 文档类扩展名（与服务端 DocumentTypes 保持一致） */
public final class DocumentTypes {

    public static final List<String> EXTENSIONS = List.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt");

    private DocumentTypes() {
    }

    public static boolean isDocumentFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot >= fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXTENSIONS.contains(ext);
    }

    public static boolean isDocumentPath(String path) {
        if (path == null) {
            return false;
        }
        if (path.startsWith("uploads/documents/")) {
            return true;
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return isDocumentFileName(name);
    }
}
