package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.auth.model.RoleCode;
import com.amiraharcs.loyalty.common.api.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

class GameLifeTimingTests extends GameLifeTestSupport {
    @Test void consumesOnlyAvailableLivesWithIndependentServerTimestampSnapshots() throws Exception {
        var browser=login(RoleCode.CUSTOMER);var owner=customer(browser);
        assertThat(snapshot(browser,NOW).availableLives()).isEqualTo(3);
        for(int i=1;i<=3;i++) {
            var consumption=consume(browser);
            assertThat(consumption.getCustomerId()).isEqualTo(owner);
            assertThat(consumption.getConsumedAt()).isEqualTo(NOW);
            assertThat(consumption.getRechargeAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
            var state=lives.current();assertThat(state.availableLives()).isEqualTo(3-i);
            assertThat(state.nextRechargeAt()).isEqualTo(NOW.plusSeconds(7200));
            assertThat(state.serverTime()).isEqualTo(NOW);assertThat(version(owner)).isEqualTo(i);
        }
        var version=version(owner);
        assertThatThrownBy(()->consume(browser)).isInstanceOf(NoGameLivesException.class)
                .satisfies(error->assertThat(((NoGameLivesException)error).code()).isEqualTo(ApiErrorCode.NO_GAME_LIVES));
        assertThat(ApiErrorCode.NO_GAME_LIVES.status().value()).isEqualTo(409);
        assertThat(consumed(owner)).isEqualTo(3);assertThat(version(owner)).isEqualTo(version);
    }
    @Test void lifeReturnsAtExactBoundaryAndHistoryRemains() throws Exception {
        var browser=login(RoleCode.CUSTOMER);consume(browser);var owner=customer(browser);
        assertThat(snapshot(browser,NOW.plusSeconds(7199).plusNanos(999999000)).availableLives()).isEqualTo(2);
        var exact=snapshot(browser,NOW.plusSeconds(7200));
        assertThat(exact.availableLives()).isEqualTo(3);assertThat(exact.nextRechargeAt()).isNull();
        assertThat(snapshot(browser,NOW.plusSeconds(7201)).availableLives()).isEqualTo(3);
        assertThat(consumed(owner)).isEqualTo(1);assertThat(version(owner)).isEqualTo(1);
        consume(browser);assertThat(consumed(owner)).isEqualTo(2);
        assertThat(lives.current().availableLives()).isEqualTo(2);
    }
    @Test void staggeredLivesRechargeAtElevenElevenTenAndElevenTwenty() throws Exception {
        Instant start=Instant.parse("2026-10-14T09:00:00Z");when(clock.instant()).thenReturn(start);
        var browser=login(RoleCode.CUSTOMER);
        for(int i=0;i<3;i++) {when(clock.instant()).thenReturn(start.plusSeconds(i*600));consume(browser);}
        var instants=new String[]{"10:59", "11:00", "11:09", "11:10", "11:20"};
        var expectedLives=new int[]{0,1,1,2,3};
        var expectedNext=new String[]{"11:00", "11:10", "11:10", "11:20",null};
        for(int i=0;i<instants.length;i++) {
            Instant now=Instant.parse("2026-10-14T"+instants[i]+":00Z");var response=snapshot(browser,now);
            assertThat(response.availableLives()).as(instants[i]).isEqualTo(expectedLives[i]);
            assertThat(response.nextRechargeAt()).isEqualTo(expectedNext[i]==null?null:Instant.parse("2026-10-14T"+expectedNext[i]+":00Z"));
            assertThat(response.serverTime()).isEqualTo(now);
        }
        assertThat(consumed(customer(browser))).isEqualTo(3);assertThat(version(customer(browser))).isEqualTo(3);
    }
    @Test void midnightDoesNotResetLives() throws Exception {
        Instant before=Instant.parse("2026-10-14T23:50:00Z");when(clock.instant()).thenReturn(before);
        var browser=login(RoleCode.CUSTOMER);consume(browser);
        var midnight=snapshot(browser,Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(midnight.availableLives()).isEqualTo(2);
        assertThat(midnight.nextRechargeAt()).isEqualTo(Instant.parse("2026-10-15T01:50:00Z"));
    }
    @ParameterizedTest @ValueSource(strings={"2026-03-29T00:30:00Z","2026-10-25T00:30:00Z"})
    void rechargeUsesTwoElapsedHoursAcrossBothLondonDstChanges(String start) throws Exception {
        Instant instant=Instant.parse(start);when(clock.instant()).thenReturn(instant);
        var browser=login(RoleCode.CUSTOMER);var consumption=consume(browser);
        assertThat(consumption.getRechargeAt()).isEqualTo(instant.plusSeconds(7200));
        assertThat(snapshot(browser,instant.plusSeconds(7200)).availableLives()).isEqualTo(3);
    }
    @Test void serverTimeUsesDatabasePrecisionWithoutRoundingRechargeEarly() throws Exception {
        var browser=login(RoleCode.CUSTOMER);
        Instant instant=NOW.plusNanos(123456789);when(clock.instant()).thenReturn(instant);
        var consumed=consume(browser);var stored=jdbc.queryForObject("SELECT consumed_at FROM loyalty.game_life_consumptions WHERE id=?",java.sql.Timestamp.class,consumed.getId());
        assertThat(consumed.getConsumedAt()).isEqualTo(NOW.plusNanos(123456000));
        assertThat(stored.toInstant()).isEqualTo(consumed.getConsumedAt());
        assertThat(snapshot(browser,consumed.getRechargeAt().minusNanos(1000)).availableLives()).isEqualTo(2);
        assertThat(snapshot(browser,consumed.getRechargeAt()).availableLives()).isEqualTo(3);
    }
}
