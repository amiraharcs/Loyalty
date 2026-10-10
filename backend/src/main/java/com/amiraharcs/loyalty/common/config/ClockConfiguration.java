package com.amiraharcs.loyalty.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One injectable UTC clock for server-owned timestamps; tests may replace it. */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {
    @Bean
    Clock applicationClock() { return Clock.systemUTC(); }
}
