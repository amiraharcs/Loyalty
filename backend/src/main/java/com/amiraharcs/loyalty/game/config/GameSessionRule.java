package com.amiraharcs.loyalty.game.config;

import java.time.Duration;

/** Settings are snapshotted per run; configuration changes never rewrite existing sessions. */
public record GameSessionRule(Duration lifetime, String engineVersion) {
    public GameSessionRule {
        if (lifetime == null || lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofMinutes(15)) > 0 || lifetime.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Session lifetime must be positive, at most 15 minutes, with microsecond precision");
        }
        if (engineVersion == null || !engineVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}")) {
            throw new IllegalArgumentException("Invalid server engine version");
        }
    }
}
