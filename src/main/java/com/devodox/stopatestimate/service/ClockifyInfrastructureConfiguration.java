package com.devodox.stopatestimate.service;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ClockifyInfrastructureConfiguration {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // RES-03: split timeouts per API surface. Backend calls are pagination-heavy (listProjects,
    // filterUsers, listInProgressTimeEntries) and need a longer read window on large workspaces;
    // reports return single but larger payloads so get even more headroom. Without timeouts the
    // JDK HttpClient defaults are "wait forever", which pins webhook threads under load.
    @Bean(name = "clockifyBackendRestClient")
    public RestClient clockifyBackendRestClient() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(30));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Bean(name = "clockifyReportsRestClient")
    public RestClient clockifyReportsRestClient() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(45));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
}
