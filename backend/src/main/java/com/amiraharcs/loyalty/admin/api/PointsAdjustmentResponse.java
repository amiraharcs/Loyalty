package com.amiraharcs.loyalty.admin.api;

import java.time.Instant;
import java.util.UUID;

public record PointsAdjustmentResponse(UUID transactionId, long signedAmount, String reason, Instant createdAt, long newBalance) { }
