package com.yyba.cloud.util;

import org.apache.commons.io.FilenameUtils;

import java.util.List;
import java.util.Locale;

/** 文档类扩展名（Android 与服务端保持一致） */
public final class DocumentTypes {

    public static final List<String> EXTENSIONS = List.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt");

    private DocumentTypes() {
    }

    public static boolean isDocumentExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String ext = FilenameUtils.getExtension(fileName).toLowerCase(Locale.ROOT);
        return !ext.isEmpty() && EXTENSIONS.contains(ext);
    }
}
