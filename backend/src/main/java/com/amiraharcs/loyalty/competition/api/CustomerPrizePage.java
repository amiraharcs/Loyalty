package com.amiraharcs.loyalty.competition.api;

import java.util.List;

public record CustomerPrizePage(List<CustomerPrizeResponse> items,String nextCursor,boolean hasNext) { }
