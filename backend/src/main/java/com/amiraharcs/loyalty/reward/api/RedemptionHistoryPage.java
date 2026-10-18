package com.amiraharcs.loyalty.reward.api;

import java.util.List;

public record RedemptionHistoryPage(List<RedemptionDetails> items,String nextCursor,boolean hasNext) { }
