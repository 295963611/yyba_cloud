package com.yyba.cloud.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentDispositionUtilTest {

    @Test
    void attachmentSupportsChineseFileName() {
        String header = ContentDispositionUtil.attachment("6月13日 骑行.mp4");
        assertTrue(header.startsWith("attachment;"));
        assertTrue(header.contains("filename*="));
        assertTrue(header.contains("UTF-8"));
    }

    @Test
    void attachmentUsesOnlyLatin1SafeCharacters() {
        String header = ContentDispositionUtil.attachment("测试视频.mp4");
        for (int i = 0; i < header.length(); i++) {
            assertTrue(header.charAt(i) <= 255);
        }
    }
}
