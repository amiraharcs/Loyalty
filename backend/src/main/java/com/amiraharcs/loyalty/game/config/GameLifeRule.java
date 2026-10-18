package com.amiraharcs.loyalty.game.config;

import java.time.Duration;

/** Server-owned rule. Persisted recharge timestamps remain snapshots if configuration changes. */
public record GameLifeRule(int maxLives, Duration rechargeInterval) {
    public GameLifeRule {
        if (maxLives < 1 || maxLives > 3) {
            throw new IllegalArgumentException("Game maximum lives must be between one and three");
        }
        if (rechargeInterval == null || rechargeInterval.isNegative() || rechargeInterval.isZero()
                || rechargeInterval.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Game recharge interval must be positive with microsecond precision");
        }
    }
}
