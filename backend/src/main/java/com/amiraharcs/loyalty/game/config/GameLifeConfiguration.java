package com.amiraharcs.loyalty.game.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GameLifeConfiguration {
    @Bean
    GameLifeRule gameLifeRule(@Value("${loyalty.game-lives.max-lives:3}") int maxLives,
            @Value("${loyalty.game-lives.recharge-interval:PT2H}") String rechargeInterval) {
        return new GameLifeRule(maxLives, Duration.parse(rechargeInterval));
    }
}
