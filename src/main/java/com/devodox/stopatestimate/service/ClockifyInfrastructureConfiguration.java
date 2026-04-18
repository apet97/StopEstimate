package com.devodox.stopatestimate.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockifyInfrastructureConfiguration {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
