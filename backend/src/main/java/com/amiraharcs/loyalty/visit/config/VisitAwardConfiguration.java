package com.amiraharcs.loyalty.visit.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class VisitAwardConfiguration {
    @Bean
    VisitAwardRule visitAwardRule(@Value("${loyalty.visits.points-per-visit:100}") long points) {
        return new VisitAwardRule(points);
    }
}
