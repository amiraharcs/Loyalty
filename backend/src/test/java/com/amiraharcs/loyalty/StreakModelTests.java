package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.loyalty.model.Streak;
import com.amiraharcs.loyalty.visit.model.Visit;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class StreakModelTests {
    private final LocalDate first = LocalDate.of(2026, 10, 12);
    @Test void emptyStateHasNoInventedHistory() {
        var streak = new Streak(UUID.randomUUID());
        assertThat(streak.getCurrentCount()).isZero();
        assertThat(streak.getLongestCount()).isZero();
        assertThat(streak.getLastQualifyingDate()).isNull();
        assertThat(streak.displayedCurrent(first)).isZero();
    }
    @Test void consecutiveDatesIncrementAndGapResetsWhileRetainingLongest() {
        var streak = new Streak(UUID.randomUUID());
        for (int day = 0; day < 4; day++) {
            streak.recordQualifyingDate(first.plusDays(day));
            assertThat(streak.getCurrentCount()).isEqualTo(day + 1);
            assertThat(streak.getLongestCount()).isEqualTo(day + 1);
        }
        streak.recordQualifyingDate(first.plusDays(5));
        assertThat(streak.getCurrentCount()).isEqualTo(1);
        assertThat(streak.getLongestCount()).isEqualTo(4);
        assertThat(streak.getLastQualifyingDate()).isEqualTo(first.plusDays(5));
    }
    @Test void sameDateIsNoOpAndOlderDateCannotCorruptProjection() {
        var streak = new Streak(UUID.randomUUID()); streak.recordQualifyingDate(first);
        streak.recordQualifyingDate(first);
        assertThatThrownBy(() -> streak.recordQualifyingDate(first.minusDays(1))).isInstanceOf(IllegalStateException.class);
        assertThat(streak.getCurrentCount()).isEqualTo(1);
        assertThat(streak.getLongestCount()).isEqualTo(1);
        assertThat(streak.getLastQualifyingDate()).isEqualTo(first);
    }
    @Test void displayExpiryNeverChangesStoredHistoryAndNextVisitResets() {
        var streak = new Streak(UUID.randomUUID());
        streak.recordQualifyingDate(first); streak.recordQualifyingDate(first.plusDays(1));
        assertThat(streak.displayedCurrent(first.plusDays(1))).isEqualTo(2);
        assertThat(streak.displayedCurrent(first.plusDays(2))).isEqualTo(2);
        assertThat(streak.displayedCurrent(first.plusDays(3))).isZero();
        assertThat(streak.displayedCurrent(first.minusDays(1))).isZero();
        assertThat(streak.getCurrentCount()).isEqualTo(2);
        assertThat(streak.getLongestCount()).isEqualTo(2);
        assertThat(streak.getLastQualifyingDate()).isEqualTo(first.plusDays(1));
        streak.recordQualifyingDate(first.plusDays(4));
        assertThat(streak.getCurrentCount()).isEqualTo(1);
        assertThat(streak.getLongestCount()).isEqualTo(2);
    }
    @ParameterizedTest
    @CsvSource({"2026-07-01T22:59:59Z,2026-07-01T23:00:00Z", "2026-03-28T12:00:00Z,2026-03-29T11:00:00Z", "2026-10-24T11:00:00Z,2026-10-25T12:00:00Z"})
    void londonCalendarDaysAdvanceAcrossMidnightAndBothDstTransitions(String before, String after) {
        var streak = new Streak(UUID.randomUUID());
        streak.recordQualifyingDate(Visit.businessDateAt(Instant.parse(before)));
        streak.recordQualifyingDate(Visit.businessDateAt(Instant.parse(after)));
        assertThat(streak.getCurrentCount()).isEqualTo(2);
    }
}
