package com.amiraharcs.loyalty;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Synthetic random signing material exists only in memory for the test JVM. */
final class QrTestSigningProperties {
    private static final String KEY = newKey();
    private QrTestSigningProperties() { }
    private static String newKey() {
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        return java.util.Base64.getEncoder().encodeToString(key);
    }
    static void register(DynamicPropertyRegistry registry) {
        registry.add("QR_ISSUER", () -> "loyalty-test");
        registry.add("QR_AUDIENCE", () -> "loyalty-test-identity");
        registry.add("QR_ACTIVE_KEY_ID", () -> "test-key");
        registry.add("QR_SIGNING_KEY_BASE64", () -> KEY);
    }
}
