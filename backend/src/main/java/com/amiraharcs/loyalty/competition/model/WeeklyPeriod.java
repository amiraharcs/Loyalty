package com.amiraharcs.loyalty.competition.model;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

/** Immutable London week boundaries. Settlement is intentionally deferred. */
@Entity
@Table(name = "weekly_periods", schema = "loyalty")
public class WeeklyPeriod {
    @Id private UUID id;
    @Column(name="start_date",nullable=false,updatable=false) private LocalDate startDate;
    @Column(name="start_at",nullable=false,updatable=false) private Instant startAt;
    @Column(name="end_at",nullable=false,updatable=false) private Instant endAt;
    @Column(name="settlement_cutoff_at",nullable=false,updatable=false) private Instant settlementCutoffAt;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=16) private WeeklyPeriodStatus status;
    protected WeeklyPeriod() { }
    public UUID getId() { return id; }
    public LocalDate getStartDate() { return startDate; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public Instant getSettlementCutoffAt() { return settlementCutoffAt; }
    public WeeklyPeriodStatus getStatus() { return status; }
}
