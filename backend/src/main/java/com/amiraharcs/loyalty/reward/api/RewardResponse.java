package com.amiraharcs.loyalty.reward.api;

import com.amiraharcs.loyalty.reward.model.Reward;
import java.time.Instant;
import java.util.UUID;

public record RewardResponse(UUID id, String name, String description, long pointCost, Instant availableFrom, Instant availableUntil) {
    public static RewardResponse from(Reward reward) {
        return new RewardResponse(reward.getId(), reward.getName(), reward.getDescription(), reward.getPointCost(), reward.getAvailableFrom(), reward.getAvailableUntil());
    }
}
