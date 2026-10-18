package com.amiraharcs.loyalty.reward.api;

import com.amiraharcs.loyalty.reward.model.*;
import java.time.Instant;
import java.util.UUID;

public record FulfilmentResponse(UUID redemptionId,RedemptionStatus status,UUID storeId,Instant fulfilledAt) {
    public static FulfilmentResponse from(RewardRedemption row) {
        return new FulfilmentResponse(row.getId(),row.getStatus(),row.getStore().getId(),row.getFulfilledAt());
    }
}
