package com.yyba.cloud.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DiscoveryCryptoTest {

    @Test
    void roundTripEncryptDecrypt() {
        String cipher = DiscoveryCrypto.encryptEndpoint("192.168.1.10", 8080);
        DiscoveryCrypto.Endpoint endpoint = DiscoveryCrypto.decryptEndpoint(cipher);
        assertNotNull(endpoint);
        assertEquals("192.168.1.10", endpoint.host);
        assertEquals(8080, endpoint.port);
    }

    @Test
    void fixedIvVectorForCrossPlatform() {
        byte[] iv = new byte[12];
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) i;
        }
        String cipher = DiscoveryCrypto.encryptEndpointForTest("192.168.1.10", 8080, iv);
        DiscoveryCrypto.Endpoint endpoint = DiscoveryCrypto.decryptEndpoint(cipher);
        assertNotNull(endpoint);
        assertEquals("192.168.1.10", endpoint.host);
        assertEquals(8080, endpoint.port);
    }

    @Test
    void invalidCipherReturnsNull() {
        assertNull(DiscoveryCrypto.decryptEndpoint("not-valid-base64!!!"));
    }
}
