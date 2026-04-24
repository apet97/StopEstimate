package com.devodox.stopatestimate.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClockifyPayloadDriftTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        ClockifyPayloadDrift.resetSeen();
        logger = (Logger) LoggerFactory.getLogger(ClockifyPayloadDriftTest.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        ClockifyPayloadDrift.resetSeen();
    }

    @Test
    void noDriftWhenAllFieldsKnown() {
        JsonObject payload = new JsonObject();
        payload.addProperty("workspaceId", "ws-1");
        payload.addProperty("addonId", "addon-1");

        ClockifyPayloadDrift.warnUnknownTopLevelFields(
                logger, "lifecycle.deleted", payload, Set.of("workspaceId", "addonId"));

        assertThat(appender.list).isEmpty();
    }

    @Test
    void unknownFieldLogsWarnOnce() {
        JsonObject payload = new JsonObject();
        payload.addProperty("workspaceId", "ws-1");
        payload.addProperty("surpriseFlag", true);

        ClockifyPayloadDrift.warnUnknownTopLevelFields(
                logger, "lifecycle.deleted", payload, Set.of("workspaceId"));
        ClockifyPayloadDrift.warnUnknownTopLevelFields(
                logger, "lifecycle.deleted", payload, Set.of("workspaceId"));

        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage())
                .contains("lifecycle.deleted")
                .contains("surpriseFlag");
    }

    @Test
    void differentUnknownSetsLogSeparately() {
        JsonObject first = new JsonObject();
        first.addProperty("workspaceId", "ws-1");
        first.addProperty("fieldA", 1);

        JsonObject second = new JsonObject();
        second.addProperty("workspaceId", "ws-2");
        second.addProperty("fieldB", 1);

        ClockifyPayloadDrift.warnUnknownTopLevelFields(
                logger, "lifecycle.deleted", first, Set.of("workspaceId"));
        ClockifyPayloadDrift.warnUnknownTopLevelFields(
                logger, "lifecycle.deleted", second, Set.of("workspaceId"));

        long warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
        assertThat(warns).isEqualTo(2);
    }

    @Test
    void nullInputsAreTolerated() {
        ClockifyPayloadDrift.warnUnknownTopLevelFields(logger, "ctx", null, Set.of("a"));
        ClockifyPayloadDrift.warnUnknownTopLevelFields(logger, "ctx", new JsonObject(), null);

        assertThat(appender.list).isEmpty();
    }
}
