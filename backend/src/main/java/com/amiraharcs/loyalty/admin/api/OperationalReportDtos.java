package com.amiraharcs.loyalty.admin.api;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Explicit aggregate contracts: no persistence identities except operational store IDs. */
public final class OperationalReportDtos {
    private OperationalReportDtos() { }
    public record InstantRange(Instant from, Instant to) { }
    public record DateRange(LocalDate from, LocalDate to) { }
    public record Amount(long count, long points) { }
    public record Rewards(long available, long future, long inactive, long ended) { }
    public record Week(LocalDate startDate, Instant startAt, Instant endAt, String status, boolean materialized,
            long eligibleParticipants, long acceptedScores, List<TopEntry> top) { }
    public record TopEntry(long rank, String alias, long score) { }
    public record Overview(Instant asOf, LocalDate businessDate, long activeCustomers, long activeStaff, long activeStores,
            long visitsToday, long pointsEarnedToday, long pointsRedeemedToday, long pendingRedemptions,
            long fulfilledRedemptionsToday, Rewards rewards, long activeGameSessions, long acceptedScoresToday,
            long availablePrizes, Week currentWeek, boolean settlementImplemented) { }
    public record VisitDay(LocalDate businessDate, long visits) { }
    public record StoreVisits(UUID storeId, long visits) { }
    public record Visits(DateRange range, UUID storeId, long totalVisits, long uniqueCustomers,
            List<VisitDay> byBusinessDate, List<StoreVisits> byStore, boolean hasMoreStores) { }
    public record Points(InstantRange range, UUID storeId, Amount earned, Amount redeemed, Amount refunded,
            Amount positiveAdjustments, Amount negativeAdjustments, boolean unattributedTypesExcluded) { }
    public record RedemptionState(String status, long count, long pointsCostSnapshot) { }
    public record StoreRedemptions(UUID storeId, long count, long pointsCostSnapshot) { }
    public record Redemptions(InstantRange range, Instant asOf, UUID storeId, List<RedemptionState> states,
            List<StoreRedemptions> byStore, boolean hasMoreStores) { }
    public record Game(InstantRange range, Instant asOf, long sessionsStarted, long completed, long expired,
            long activeInRange, long uniquePlayers, long acceptedScores, BigDecimal averageAcceptedScore,
            Long maxAcceptedScore, long activeSessionsNow) { }
    public record PrizeState(String status, long count) { }
    public record PrizeTrigger(String trigger, long count) { }
    public record Competition(InstantRange range, Instant asOf, Week currentWeek, long weeklyPrizeDefinitions,
            long activeWeeklyPrizeDefinitions, long prizeAssignments, List<PrizeState> states,
            List<PrizeTrigger> triggers, boolean settlementImplemented) { }
}
