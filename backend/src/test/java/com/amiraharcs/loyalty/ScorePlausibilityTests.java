package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.game.model.GameSession;
import com.amiraharcs.loyalty.game.service.ScorePlausibility;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ScorePlausibilityTests {
    final Instant start=Instant.parse("2026-10-18T10:00:00Z");
    final ScorePlausibility validator=new ScorePlausibility();
    GameSession session(String engine) {return new GameSession(UUID.randomUUID(),start,start.plusSeconds(1200),engine);}
    @Test void deterministicWholeSecondFormulaAndLifetimeCap() {
        var run=session("v1");assertThat(validator.ceiling(run,start)).isEqualTo(100);
        assertThat(validator.ceiling(run,start.plusSeconds(10))).isEqualTo(3600);
        assertThat(validator.ceiling(run,start.plusSeconds(10).plusNanos(999999999))).isEqualTo(3600);
        assertThat(validator.ceiling(run,start.plusSeconds(899).plusNanos(999999999))).isEqualTo(314750);
        assertThat(validator.ceiling(run,start.plusSeconds(900))).isEqualTo(315100);
        assertThat(validator.ceiling(run,start.plusSeconds(1200))).isEqualTo(315100);
    }
    @Test void unknownEnginesAndBackwardsClockFailClosed() {
        assertThatThrownBy(()->validator.ceiling(session("v2"),start)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->validator.ceiling(session("v1"),start.minusNanos(1))).isInstanceOf(IllegalStateException.class);
    }
}
