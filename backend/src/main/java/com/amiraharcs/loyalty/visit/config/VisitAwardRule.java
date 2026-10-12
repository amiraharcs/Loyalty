package com.amiraharcs.loyalty.visit.config;

import com.amiraharcs.loyalty.loyalty.model.PointValues;

/** Server-owned immutable rule; configuration changes affect new confirmations only. */
public record VisitAwardRule(long pointsPerVisit) {
    public VisitAwardRule {
        if (pointsPerVisit < 1 || pointsPerVisit > PointValues.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("Points per visit must be a positive supported integer");
        }
    }
}
