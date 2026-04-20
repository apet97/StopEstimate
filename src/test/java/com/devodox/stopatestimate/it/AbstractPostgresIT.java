package com.devodox.stopatestimate.it;

import com.devodox.stopatestimate.model.AddonStatus;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.WebhookCredential;
import com.devodox.stopatestimate.store.InstallationStore;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

@SpringBootTest
@ActiveProfiles("it")
@Testcontainers
public abstract class AbstractPostgresIT {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withReuse(false);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected InstallationStore installationStore;

    @Autowired
    @Qualifier("lifecycleReconcileExecutor")
    private ThreadPoolTaskExecutor lifecycleReconcileExecutor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE webhook_events,
                               guard_events,
                               cutoff_jobs,
                               project_lock_snapshots,
                               webhook_registrations,
                               installations,
                               shedlock
                RESTART IDENTITY CASCADE
                """);
    }

    @AfterEach
    void awaitAsyncReconcileIdle() {
        Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(20))
                .until(() -> lifecycleReconcileExecutor.getActiveCount() == 0
                        && lifecycleReconcileExecutor.getQueueSize() == 0);
    }

    protected InstallationRecord installation(String workspaceId) {
        Instant now = Instant.parse("2026-04-20T10:00:00Z");
        return new InstallationRecord(
                workspaceId,
                "addon-123",
                "addon-user-123",
                "owner-user-123",
                "installation-token-" + workspaceId,
                "https://api.clockify.me/api",
                "https://reports.api.clockify.me",
                Map.of(),
                AddonStatus.ACTIVE,
                true,
                "ENFORCE",
                "MONTHLY",
                now,
                now);
    }

    protected InstallationRecord installationWithWebhook(
            String workspaceId,
            String routePath,
            String eventType,
            String webhookToken) {
        InstallationRecord base = installation(workspaceId);
        return new InstallationRecord(
                base.workspaceId(),
                base.addonId(),
                base.addonUserId(),
                base.ownerUserId(),
                base.installationToken(),
                base.backendUrl(),
                base.reportsUrl(),
                Map.of(routePath, new WebhookCredential(eventType, webhookToken)),
                base.status(),
                base.enabled(),
                base.enforcementMode(),
                base.defaultResetCadence(),
                base.installedAt(),
                base.updatedAt(),
                base.timezone());
    }

    protected int countRows(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    protected static String installedPayload(String workspaceId, String installationToken, String webhookToken) {
        return """
                {
                  "addonId": "addon-123",
                  "authToken": "%s",
                  "workspaceId": "%s",
                  "asUser": "user-123",
                  "apiUrl": "https://api.clockify.me/api",
                  "addonUserId": "addon-user-123",
                  "settings": [
                    {"id": "enabled", "value": true},
                    {"id": "defaultResetCadence", "value": "MONTHLY"}
                  ],
                  "webhooks": [
                    {
                      "path": "https://it.example.com/webhook/new-time-entry",
                      "webhookType": "ADDON",
                      "authToken": "%s"
                    }
                  ]
                }
                """.formatted(installationToken, workspaceId, webhookToken);
    }
}
