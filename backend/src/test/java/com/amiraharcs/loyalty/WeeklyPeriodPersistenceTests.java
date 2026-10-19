package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.competition.service.WeeklyPeriodService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class WeeklyPeriodPersistenceTests extends LeaderboardTestSupport {
    @Autowired WeeklyPeriodService periods;
    @ParameterizedTest @CsvSource({
        "2026-03-29T01:00:00Z,2026-03-23T00:00:00Z,2026-03-29T23:00:00Z,167",
        "2026-10-25T01:00:00Z,2026-10-18T23:00:00Z,2026-10-26T00:00:00Z,169",
        "2026-10-11T22:59:59.999999Z,2026-10-04T23:00:00Z,2026-10-11T23:00:00Z,168",
        "2026-10-11T23:00:00Z,2026-10-11T23:00:00Z,2026-10-18T23:00:00Z,168"})
    void databaseAndJavaAgreeOnDstAndExactBoundaryMembership(String input,String start,String end,int hours) {
        var instant=Instant.parse(input);var period=periods.containing(instant);
        assertThat(period.getStartDate()).isEqualTo(WeeklyPeriodService.startDate(instant));
        assertThat(period.getStartAt()).isEqualTo(Instant.parse(start));assertThat(period.getEndAt()).isEqualTo(Instant.parse(end));
        assertThat(Duration.between(period.getStartAt(),period.getEndAt()).toHours()).isEqualTo(hours);
        assertThat(period.getSettlementCutoffAt()).isEqualTo(period.getEndAt().plusSeconds(900));
        assertThat(periods.containing(instant).getId()).isEqualTo(period.getId());
    }
    @Test void actualStartReturnsGeneratedPeriodAndCorrectLondonWeekLink() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);
        UUID periodId=jdbc.queryForObject("SELECT weekly_period_id FROM loyalty.game_sessions WHERE id=?",UUID.class,run.sessionId());
        assertThat(periodId).isEqualTo(periods.containing(NOW).getId());
        var managed=gameEntities.find(com.amiraharcs.loyalty.game.model.GameSession.class,run.sessionId());
        assertThat(managed.getWeeklyPeriodId()).isEqualTo(periodId);
    }
    @Test void aRunStartingAtBoundaryGetsNewPeriodEvenWhenOtherRunsCompleteLater() throws Exception {
        var boundary=Instant.parse("2026-10-11T23:00:00Z");
        when(clock.instant()).thenReturn(boundary);var browser=login(RoleCode.CUSTOMER);var session=start(browser);
        assertThat(jdbc.queryForObject("SELECT p.start_date FROM loyalty.game_sessions s JOIN loyalty.weekly_periods p ON p.id=s.weekly_period_id WHERE s.id=?",java.sql.Date.class,session.sessionId()).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-12"));
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentCreatorsProduceOnePeriodAndSameId() throws Exception {
        Instant time=Instant.parse("2088-05-12T12:00:00Z");var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(6)) {
            List<Future<UUID>> results=new ArrayList<>();
            for(int i=0;i<6;i++) {results.add(pool.submit(()->{gate.await();return periods.containing(time).getId();}));}
            gate.countDown();Set<UUID> ids=new HashSet<>();
            for(var result:results) {ids.add(result.get(30,TimeUnit.SECONDS));}
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty.weekly_periods WHERE start_date=?",Long.class,WeeklyPeriodService.startDate(time))).isEqualTo(1);
        }
    }
    @Test void databaseRejectsForgedAndChangedMembershipAndInvalidPeriodBoundaries() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var run=start(browser);
        var other=periods.containing(NOW.minusSeconds(7*86400));
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            for(String sql:List.of("UPDATE loyalty.game_sessions SET weekly_period_id='"+other.getId()+"' WHERE id='"+run.sessionId()+"'",
                    "UPDATE loyalty.weekly_periods SET end_at=start_at WHERE id='"+other.getId()+"'",
                    "INSERT INTO loyalty.game_sessions(id,customer_id,started_at,expires_at,status,engine_version,weekly_period_id) VALUES ('"+UUID.randomUUID()+"','"+customer(browser)+"','"+NOW+"','"+NOW.plusSeconds(900)+"','EXPIRED','v1','"+other.getId()+"')")) {
                var savepoint=connection.setSavepoint();
                try(var statement=connection.createStatement()) {
                    assertThatThrownBy(()->statement.executeUpdate(sql)).isInstanceOf(java.sql.SQLException.class)
                            .satisfies(error->assertThat(((java.sql.SQLException)error).getSQLState()).isEqualTo("23514"));
                } finally {connection.rollback(savepoint);connection.releaseSavepoint(savepoint);}
            }
            return null;
        });
    }
}
