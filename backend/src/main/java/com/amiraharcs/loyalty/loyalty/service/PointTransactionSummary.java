package com.amiraharcs.loyalty.loyalty.service;

import com.amiraharcs.loyalty.loyalty.model.PointTransactionType;
import java.time.Instant;
import java.util.UUID;

/** Internal safe history projection, without identity, actor or adjustment notes. */
public record PointTransactionSummary(UUID id, long signedAmount, PointTransactionType type, Instant createdAt) { }
