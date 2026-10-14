package com.amiraharcs.loyalty.qr.config;

import java.util.*;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class QrSigningConfiguration {
    @Bean
    QrSigningSettings qrSigningSettings(@Value("${loyalty.qr.issuer:}") String issuer,
            @Value("${loyalty.qr.audience:}") String audience,
            @Value("${loyalty.qr.active-key-id:}") String activeKeyId,
            @Value("${loyalty.qr.signing-key-base64:}") String signingKey,
            @Value("${loyalty.qr.verification-keys:}") String verificationKeys, JsonMapper json) {
        try {
            if (issuer.isBlank() || audience.isBlank() || issuer.length() > 200 || audience.length() > 200
                    || !issuer.equals(issuer.strip()) || !audience.equals(audience.strip())) { throw new IllegalArgumentException(); }
            validateKeyId(activeKeyId);
            Map<String, SecretKey> keys = new HashMap<>();
            if (!verificationKeys.isBlank()) {
                var ring = json.readTree(verificationKeys);
                if (!ring.isObject() || ring.size() > 10) { throw new IllegalArgumentException(); }
                for (var property : ring.properties()) {
                    validateKeyId(property.getKey());
                    if (!property.getValue().isString() || property.getKey().equals(activeKeyId)) { throw new IllegalArgumentException(); }
                    keys.put(property.getKey(), decode(property.getValue().asText()));
                }
            }
            keys.put(activeKeyId, decode(signingKey));
            return new QrSigningSettings(issuer, audience, activeKeyId, keys);
        } catch (Exception invalid) {
            // Discard parser causes/messages, which can contain secret configuration values.
            throw new IllegalArgumentException("QR signing configuration requires issuer, audience, valid key IDs and Base64 keys of at least 256 bits; verification keys must exclude the active ID");
        }
    }
    private static void validateKeyId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) { throw new IllegalArgumentException(); }
    }
    private static SecretKey decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length < 32 || bytes.length > 128) { throw new IllegalArgumentException(); }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
