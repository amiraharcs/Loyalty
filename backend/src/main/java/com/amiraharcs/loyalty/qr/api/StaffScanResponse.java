package com.amiraharcs.loyalty.qr.api;

import java.time.Instant;
import java.util.UUID;

public record StaffScanResponse(UUID scanResolutionId, Instant expiresAt, String displayName, long balance) { }
