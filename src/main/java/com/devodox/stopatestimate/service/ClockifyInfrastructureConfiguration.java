package com.devodox.stopatestimate.service;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ClockifyInfrastructureConfiguration {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RestClientCustomizer clockifyRestClientTimeouts() {
        // Bound every Clockify call: without timeouts the JDK/Apache HttpClient defaults are
        // "wait forever", which lets a stalled Clockify socket pin a webhook thread until
        // Tomcat's pool is exhausted under load.
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(10));
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(settings));
    }
}
