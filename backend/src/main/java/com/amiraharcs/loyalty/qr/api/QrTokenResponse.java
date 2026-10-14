package com.amiraharcs.loyalty.qr.api;

import java.time.Instant;

public record QrTokenResponse(String token, Instant expiresAt, Instant serverTime) {
    @Override public String toString() { return "QrTokenResponse[redacted]"; }
}
