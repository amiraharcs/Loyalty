package com.amiraharcs.loyalty.qr.model;

import java.time.Instant;
import java.util.UUID;

/** Safe internal history projection, not an HTTP response or mutable persistence entity. */
public record QrScanEvent(UUID id, String tokenJti, UUID staffUserId, UUID storeId, Instant occurredAt,
        ScanEventOutcome outcome, String reasonCode, UUID requestId, UUID resolutionId, UUID visitId) { }
