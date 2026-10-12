package com.amiraharcs.loyalty.visit.service;

import com.amiraharcs.loyalty.visit.model.Visit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Safe internal projection; excludes customer, actor, account and credential data. */
public record VisitSummary(UUID id, UUID storeId, LocalDate businessDate, Instant confirmedAt, long earnedPoints) {
    public static VisitSummary from(Visit visit) {
        return new VisitSummary(visit.getId(), visit.getStore().getId(), visit.getBusinessDate(), visit.getConfirmedAt(), visit.getEarnedPoints());
    }
}
