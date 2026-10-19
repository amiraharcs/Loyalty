package com.amiraharcs.loyalty.competition.service;

import com.amiraharcs.loyalty.competition.model.WeeklyPeriod;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WeeklyPeriodService {
    public static final ZoneId ZONE = ZoneId.of("Europe/London");
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    public WeeklyPeriodService(JdbcTemplate jdbc, EntityManager entities) { this.jdbc=jdbc; this.entities=entities; }
    public static LocalDate startDate(Instant instant) {
        return instant.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
    public static Instant startAt(LocalDate monday) { return monday.atStartOfDay(ZONE).toInstant(); }
    /** Infrastructure get-or-create only: never settles a period or changes customer state. */
    @Transactional
    public WeeklyPeriod containing(Instant instant) {
        UUID id=jdbc.queryForObject("SELECT loyalty.ensure_weekly_period(?)",UUID.class,java.sql.Timestamp.from(instant));
        return entities.find(WeeklyPeriod.class,id);
    }
}
