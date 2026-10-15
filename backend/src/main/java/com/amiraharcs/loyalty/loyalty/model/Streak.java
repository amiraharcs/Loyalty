package com.amiraharcs.loyalty.loyalty.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Maintained projection of immutable qualifying visits. Time passing never changes stored history. */
@Entity
@Table(name = "streaks", schema = "loyalty")
public class Streak {
    @Id @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;
    @Column(name = "current_count", nullable = false) private int currentCount;
    @Column(name = "longest_count", nullable = false) private int longestCount;
    @Column(name = "last_qualifying_date") private LocalDate lastQualifyingDate;
    @Version @Column(nullable = false) private long version;
    protected Streak() { }
    public Streak(UUID customerId) { this.customerId = Objects.requireNonNull(customerId); }
    public void recordQualifyingDate(LocalDate date) {
        Objects.requireNonNull(date);
        if (lastQualifyingDate != null && date.isBefore(lastQualifyingDate)) {
            throw new IllegalStateException("Streak dates must be applied in chronological order");
        }
        if (date.equals(lastQualifyingDate)) { return; }
        currentCount = lastQualifyingDate != null && date.equals(lastQualifyingDate.plusDays(1))
                ? Math.incrementExact(currentCount) : 1;
        longestCount = Math.max(longestCount,currentCount);
        lastQualifyingDate = date;
    }
    public int displayedCurrent(LocalDate serverDate) {
        Objects.requireNonNull(serverDate);
        return lastQualifyingDate != null && !lastQualifyingDate.isAfter(serverDate)
                && !lastQualifyingDate.isBefore(serverDate.minusDays(1)) ? currentCount : 0;
    }
    public UUID getCustomerId() { return customerId; }
    public int getCurrentCount() { return currentCount; }
    public int getLongestCount() { return longestCount; }
    public LocalDate getLastQualifyingDate() { return lastQualifyingDate; }
    public long getVersion() { return version; }
}
