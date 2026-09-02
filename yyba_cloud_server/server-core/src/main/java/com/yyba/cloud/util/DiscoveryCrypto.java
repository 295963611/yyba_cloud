package com.yyba.cloud.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 家庭云盘局域网发现 endpoint 加解密（B1 固定 KEY，启动器与 Android 保持一致）。
 */
public final class DiscoveryCrypto {

    private static final String KEY_SEED = "YyBaCloud-Family-Discovery-Key-v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private static final byte[] AES_KEY = deriveKey(KEY_SEED);

    private DiscoveryCrypto() {
    }

    public static final class Endpoint {
        public final String host;
        public final int port;

        public Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public static String encryptEndpoint(String host, int port) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host required");
        }
        String payload = normalizeHost(host) + "|" + port;
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return encryptPayload(payload, iv);
    }

    public static Endpoint decryptEndpoint(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText.trim());
            if (combined.length <= IV_LENGTH) {
                return null;
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);
            String payload = decryptPayload(encrypted, iv);
            if (payload == null) {
                return null;
            }
            int sep = payload.lastIndexOf('|');
            if (sep <= 0 || sep >= payload.length() - 1) {
                return null;
            }
            String host = payload.substring(0, sep);
            int port = Integer.parseInt(payload.substring(sep + 1));
            if (port < 1 || port > 65535) {
                return null;
            }
            return new Endpoint(host, port);
        } catch (Exception e) {
            return null;
        }
    }

    /** 测试用：固定 IV 便于双端向量对齐 */
    static String encryptEndpointForTest(String host, int port, byte[] iv) {
        String payload = normalizeHost(host) + "|" + port;
        return encryptPayload(payload, iv);
    }

    private static String encryptPayload(String payload, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("discovery encrypt failed", e);
        }
    }

    private static String decryptPayload(byte[] encrypted, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(AES_KEY, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] deriveKey(String seed) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("discovery key derive failed", e);
        }
    }

    private static String normalizeHost(String host) {
        String h = host.trim();
        if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
            h = h.substring(1, h.length() - 1);
        }
        return h;
    }
}
