package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.competition.service.WeeklyPeriodService;
import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class WeeklyPeriodTests {
    @ParameterizedTest @CsvSource({
        "2026-10-11T22:59:59.999999Z,2026-10-05,2026-10-04T23:00:00Z",
        "2026-10-11T23:00:00Z,2026-10-12,2026-10-11T23:00:00Z",
        "2026-03-29T00:59:59Z,2026-03-23,2026-03-23T00:00:00Z",
        "2026-03-29T01:00:00Z,2026-03-23,2026-03-23T00:00:00Z",
        "2026-03-29T22:59:59Z,2026-03-23,2026-03-23T00:00:00Z",
        "2026-03-29T23:00:00Z,2026-03-30,2026-03-29T23:00:00Z",
        "2026-10-25T00:59:59Z,2026-10-19,2026-10-18T23:00:00Z",
        "2026-10-25T01:00:00Z,2026-10-19,2026-10-18T23:00:00Z",
        "2026-10-25T23:59:59Z,2026-10-19,2026-10-18T23:00:00Z",
        "2026-10-26T00:00:00Z,2026-10-26,2026-10-26T00:00:00Z"})
    void realLondonRulesDetermineMondayMembership(String input,String monday,String start) {
        var date=WeeklyPeriodService.startDate(Instant.parse(input));assertThat(date).isEqualTo(LocalDate.parse(monday));
        assertThat(WeeklyPeriodService.startAt(date)).isEqualTo(Instant.parse(start));
    }
}
