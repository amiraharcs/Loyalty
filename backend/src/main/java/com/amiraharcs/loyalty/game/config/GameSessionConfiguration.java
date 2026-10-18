package com.amiraharcs.loyalty.game.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GameSessionConfiguration {
    @Bean
    GameSessionRule gameSessionRule(@Value("${loyalty.game-sessions.lifetime:PT15M}") String lifetime,
            @Value("${loyalty.game-sessions.engine-version:v1}") String engineVersion) {
        return new GameSessionRule(Duration.parse(lifetime), engineVersion);
    }
}
