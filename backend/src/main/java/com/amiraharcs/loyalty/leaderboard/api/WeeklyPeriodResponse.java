package com.amiraharcs.loyalty.leaderboard.api;

import com.amiraharcs.loyalty.competition.model.*;
import java.time.*;
import java.util.UUID;

public record WeeklyPeriodResponse(UUID id, LocalDate startDate, Instant startAt, Instant endAt,
        Instant settlementCutoffAt, WeeklyPeriodStatus status) {
    public static WeeklyPeriodResponse from(WeeklyPeriod period) {
        return new WeeklyPeriodResponse(period.getId(),period.getStartDate(),period.getStartAt(),period.getEndAt(),
                period.getSettlementCutoffAt(),period.getStatus());
    }
}
