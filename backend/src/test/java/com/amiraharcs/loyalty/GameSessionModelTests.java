package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.game.config.*;
import com.amiraharcs.loyalty.game.model.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class GameSessionModelTests {
    @Test void configurationDefaultsAndServerOverridesAreValidated() {
        var runner=new ApplicationContextRunner().withUserConfiguration(GameSessionConfiguration.class);
        runner.run(context->assertThat(context.getBean(GameSessionRule.class)).isEqualTo(new GameSessionRule(Duration.ofMinutes(15),"v1")));
        runner.withPropertyValues("loyalty.game-sessions.lifetime=PT10M","loyalty.game-sessions.engine-version=v2")
                .run(context->assertThat(context.getBean(GameSessionRule.class)).isEqualTo(new GameSessionRule(Duration.ofMinutes(10),"v2")));
        runner.withPropertyValues("loyalty.game-sessions.lifetime=PT0S").run(context->assertThat(context).hasFailed());
    }
    @ParameterizedTest @ValueSource(strings={"", " ", "bad version", "<script>", "abcdefghijklmnopqrstuvwxyz0123456789too-long"})
    void engineVersionIsExplicitBoundedServerData(String version) {
        assertThatThrownBy(()->new GameSessionRule(Duration.ofMinutes(15),version)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void lifetimeMustBePositiveWithDatabasePrecision() {
        for(var duration:java.util.List.of(Duration.ZERO,Duration.ofSeconds(-1),Duration.ofNanos(1))) {
            assertThatThrownBy(()->new GameSessionRule(duration,"v1")).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void expirationIsInclusiveIdempotentAndDoesNotCompleteARun() {
        var now=Instant.parse("2026-10-18T09:00:00Z");var session=new GameSession(UUID.randomUUID(),now,now.plusSeconds(900),"v1");
        assertThat(session.expireIfStale(now.plusSeconds(900).minusNanos(1))).isFalse();
        assertThat(session.expireIfStale(now.plusSeconds(900))).isTrue();assertThat(session.getStatus()).isEqualTo(GameSessionStatus.EXPIRED);
        assertThat(session.expireIfStale(now.plusSeconds(1000))).isFalse();assertThat(session.getCompletedAt()).isNull();
        assertThat(session.getStartedAt()).isEqualTo(now);assertThat(session.getExpiresAt()).isEqualTo(now.plusSeconds(900));
    }
}
