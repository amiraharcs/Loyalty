package com.amiraharcs.loyalty.reward.api;

import java.util.List;

public record RewardPage<T>(List<T> items, String nextCursor, boolean hasNext) { }
