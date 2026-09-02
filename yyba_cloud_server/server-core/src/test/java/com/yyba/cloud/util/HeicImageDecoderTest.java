package com.yyba.cloud.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeicImageDecoderTest {

    @Test
    void isHeic_detectsExtension() {
        assertTrue(HeicImageDecoder.isHeic("photo.heic", null));
        assertTrue(HeicImageDecoder.isHeic("photo.HEIF", null));
        assertFalse(HeicImageDecoder.isHeic("photo.jpg", null));
    }

    @Test
    void isHeic_detectsMimeType() {
        assertTrue(HeicImageDecoder.isHeic("x", "image/heic"));
        assertTrue(HeicImageDecoder.isHeic("x", "image/heif-sequence"));
        assertFalse(HeicImageDecoder.isHeic("x", "image/jpeg"));
    }
}
