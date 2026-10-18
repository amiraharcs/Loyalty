package com.amiraharcs.loyalty.reward.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RedemptionRequest(@NotNull UUID rewardId, @NotNull UUID storeId) { }
