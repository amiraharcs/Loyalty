package com.amiraharcs.loyalty.reward.api;

import com.amiraharcs.loyalty.reward.model.*;
import java.time.Instant;
import java.util.UUID;

/** Command result, including the balance at creation; exact retries return this original snapshot. */
public record RedemptionResponse(UUID redemptionId, UUID rewardId, String rewardName, long pointCost,
        RedemptionStatus status, UUID storeId, Instant requestedAt, Instant expiresAt, long newBalance) {
    public static RedemptionResponse from(RewardRedemption redemption,long balance) {
        return new RedemptionResponse(redemption.getId(),redemption.getReward().getId(),redemption.getRewardNameSnapshot(),
                redemption.getPointCostSnapshot(),redemption.getStatus(),redemption.getStore().getId(),
                redemption.getRequestedAt(),redemption.getExpiresAt(),balance);
    }
}
