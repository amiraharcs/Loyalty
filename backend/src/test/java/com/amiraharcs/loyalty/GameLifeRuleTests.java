package com.amiraharcs.loyalty;

import com.amiraharcs.loyalty.game.config.GameLifeRule;
import com.amiraharcs.loyalty.game.model.GameLifeConsumption;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.amiraharcs.loyalty.game.config.GameLifeConfiguration;
import static org.assertj.core.api.Assertions.*;

class GameLifeRuleTests {
    @Test void configurationHasArchitectureDefaultsAndSupportsServerOnlyChanges() {
        var runner=new ApplicationContextRunner().withUserConfiguration(GameLifeConfiguration.class);
        runner.run(context->assertThat(context.getBean(GameLifeRule.class)).isEqualTo(new GameLifeRule(3,Duration.ofHours(2))));
        runner.withPropertyValues("loyalty.game-lives.max-lives=2","loyalty.game-lives.recharge-interval=PT1H")
                .run(context->assertThat(context.getBean(GameLifeRule.class)).isEqualTo(new GameLifeRule(2,Duration.ofHours(1))));
        runner.withPropertyValues("loyalty.game-lives.max-lives=4").run(context->assertThat(context).hasFailed());
        runner.withPropertyValues("loyalty.game-lives.recharge-interval=PT0S").run(context->assertThat(context).hasFailed());
    }
    @ParameterizedTest @ValueSource(ints={-1,0,4,Integer.MAX_VALUE})
    void rejectsUnsupportedMaximum(int max) {assertThatThrownBy(()->new GameLifeRule(max,Duration.ofHours(2))).isInstanceOf(IllegalArgumentException.class);}
    @Test void rejectsNonpositiveOrSubMicrosecondRechargeIntervals() {
        for(Duration duration:java.util.List.of(Duration.ZERO,Duration.ofSeconds(-1),Duration.ofNanos(1))) {
            assertThatThrownBy(()->new GameLifeRule(3,duration)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->new GameLifeRule(3,null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void consumptionRequiresOwnerAndStrictlyLaterRecharge() {
        var now=Instant.parse("2026-10-14T12:00:00Z");var owner=UUID.randomUUID();
        assertThatThrownBy(()->new GameLifeConsumption(owner,now,now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new GameLifeConsumption(owner,now,now.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new GameLifeConsumption(null,now,now.plusSeconds(7200))).isInstanceOf(NullPointerException.class);
    }
}
