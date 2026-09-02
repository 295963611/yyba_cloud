package com.yyba.cloud.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 环境 Key 格式（纯 JVM，不依赖 Robolectric） */
public class EnvironmentKeyTest {

    @Test
    public void of_emptyWhenMissingParts() {
        assertEquals("", EnvironmentKey.of(null, "alice"));
        assertEquals("", EnvironmentKey.of("", "alice"));
        assertEquals("", EnvironmentKey.of("http://192.168.1.10:8080", null));
        assertEquals("", EnvironmentKey.of("http://192.168.1.10:8080", ""));
    }

    @Test
    public void of_combinesServerAndUsername() {
        assertEquals("http://192.168.1.10:8080|alice",
                EnvironmentKey.of("http://192.168.1.10:8080", "alice"));
    }

    @Test
    public void of_serverChangeProducesDifferentKey() {
        String keyA = EnvironmentKey.of("http://192.168.1.10:8080", "alice");
        String keyB = EnvironmentKey.of("http://192.168.1.20:8080", "alice");
        assertFalse(keyA.equals(keyB));
    }

    @Test
    public void of_accountChangeProducesDifferentKey() {
        String keyA = EnvironmentKey.of("http://192.168.1.10:8080", "alice");
        String keyB = EnvironmentKey.of("http://192.168.1.10:8080", "bob");
        assertFalse(keyA.equals(keyB));
    }

    @Test
    public void of_sameEnvironmentStable() {
        String key1 = EnvironmentKey.of("http://10.0.0.1:8080", "user1");
        String key2 = EnvironmentKey.of("http://10.0.0.1:8080", "user1");
        assertTrue(key1.equals(key2));
    }
}
