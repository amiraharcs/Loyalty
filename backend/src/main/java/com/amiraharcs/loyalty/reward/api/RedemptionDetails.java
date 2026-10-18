package com.amiraharcs.loyalty.reward.api;

import com.amiraharcs.loyalty.reward.model.*;
import java.time.Instant;
import java.util.UUID;

/** Owned resource recovery uses retained benefit evidence, never today's mutable catalogue values. */
public record RedemptionDetails(UUID redemptionId, UUID rewardId, String rewardName, String rewardDescription,
        long pointCost, RedemptionSource source, RedemptionStatus status, UUID storeId, Instant requestedAt, Instant expiresAt, Instant fulfilledAt) {
    public static RedemptionDetails from(RewardRedemption redemption) {
        return new RedemptionDetails(redemption.getId(),redemption.getReward().getId(),redemption.getRewardNameSnapshot(),
                redemption.getRewardDescriptionSnapshot(),redemption.getPointCostSnapshot(),redemption.getSource(),
                redemption.getStatus(),redemption.getStore().getId(),redemption.getRequestedAt(),redemption.getExpiresAt(),redemption.getFulfilledAt());
    }
}
