package com.amiraharcs.loyalty.qr.config;

import java.util.Map;
import javax.crypto.SecretKey;

/** Keys have no printable representation and are never exposed through configuration endpoints. */
public final class QrSigningSettings {
    private final String issuer;
    private final String audience;
    private final String activeKeyId;
    private final Map<String, SecretKey> keys;
    public QrSigningSettings(String issuer, String audience, String activeKeyId, Map<String, SecretKey> keys) {
        this.issuer = issuer; this.audience = audience; this.activeKeyId = activeKeyId; this.keys = Map.copyOf(keys);
    }
    public String issuer() { return issuer; }
    public String audience() { return audience; }
    public String activeKeyId() { return activeKeyId; }
    public SecretKey signingKey() { return keys.get(activeKeyId); }
    public SecretKey verificationKey(String keyId) { return keyId == null ? null : keys.get(keyId); }
    @Override public String toString() { return "QrSigningSettings[redacted]"; }
}
