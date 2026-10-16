package com.amiraharcs.loyalty.reward.api;

import com.amiraharcs.loyalty.reward.model.Reward;
import java.time.Instant;
import java.util.UUID;

public record AdminRewardResponse(UUID id, String name, String description, long pointCost, boolean active,
        Instant availableFrom, Instant availableUntil, Instant createdAt, Instant updatedAt) {
    public static AdminRewardResponse from(Reward reward) {
        return new AdminRewardResponse(reward.getId(), reward.getName(), reward.getDescription(), reward.getPointCost(),
                reward.isActive(), reward.getAvailableFrom(), reward.getAvailableUntil(), reward.getCreatedAt(), reward.getUpdatedAt());
    }
}
