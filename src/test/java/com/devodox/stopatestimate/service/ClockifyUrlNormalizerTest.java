package com.devodox.stopatestimate.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClockifyUrlNormalizerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.clockify.me",
            "https://api.clockify.me/",
            "https://api.clockify.me/api",
            "https://api.clockify.me/api/v1",
            "https://api.eu.clockify.me/api",
            "https://global.api.clockify.me/api/v2",
            "https://reports.api.clockify.me"
    })
    void acceptsClockifyHosts(String raw) {
        String normalized = ClockifyUrlNormalizer.normalizeBackendApiUrl(raw);
        assertThat(normalized).startsWith("https://");
        assertThat(normalized).endsWith("/api");
    }

    @Test
    void stripsVersionSuffix() {
        assertThat(ClockifyUrlNormalizer.normalizeBackendApiUrl("https://api.clockify.me/api/v1"))
                .isEqualTo("https://api.clockify.me/api");
    }

    @Test
    void stripsTrailingSlash() {
        assertThat(ClockifyUrlNormalizer.normalizeBackendApiUrl("https://api.clockify.me/"))
                .isEqualTo("https://api.clockify.me/api");
    }

    @Test
    void lowercasesHost() {
        assertThat(ClockifyUrlNormalizer.normalizeBackendApiUrl("https://API.Clockify.ME/api"))
                .isEqualTo("https://api.clockify.me/api");
    }

    @Test
    void rejectsHttpScheme() {
        assertThatThrownBy(() -> ClockifyUrlNormalizer.normalizeBackendApiUrl("http://api.clockify.me/api"))
                .isInstanceOf(InvalidAddonTokenException.class)
                .hasMessageContaining("https");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://api.clockify.me",
            "gopher://api.clockify.me",
            "javascript:alert(1)"
    })
    void rejectsNonHttpsSchemes(String raw) {
        assertThatThrownBy(() -> ClockifyUrlNormalizer.normalizeBackendApiUrl(raw))
                .isInstanceOf(InvalidAddonTokenException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://attacker.com/api",
            "https://clockify.me.evil.com/api",
            "https://example.org/api",
            "https://api.clockify.me.evil.co/api",
            "https://localhost/api",
            "https://127.0.0.1/api"
    })
    void rejectsNonClockifyHosts(String raw) {
        assertThatThrownBy(() -> ClockifyUrlNormalizer.normalizeBackendApiUrl(raw))
                .isInstanceOf(InvalidAddonTokenException.class)
                .hasMessageContaining("host");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> ClockifyUrlNormalizer.normalizeBackendApiUrl(""))
                .isInstanceOf(InvalidAddonTokenException.class);
        assertThatThrownBy(() -> ClockifyUrlNormalizer.normalizeBackendApiUrl(null))
                .isInstanceOf(InvalidAddonTokenException.class);
    }

    @Test
    void appendsApiWhenPathMissing() {
        assertThat(ClockifyUrlNormalizer.normalizeBackendApiUrl("https://api.clockify.me"))
                .isEqualTo("https://api.clockify.me/api");
    }

    @Test
    void stripsEmbeddedCredentials() {
        // SEC-05 regression: userInfo in a crafted JWT must not reach outbound calls.
        assertThat(ClockifyUrlNormalizer.normalizeBackendApiUrl(
                "https://user:pass@api.clockify.me/api"))
                .isEqualTo("https://api.clockify.me/api");
    }

    @Test
    void stripsQueryAndFragment() {
        // SEC-05 regression: query/fragment are stripped.
        assertThat(ClockifyUrlNormalizer.normalizeBackendApiUrl(
                "https://api.clockify.me/api?injected=yes#frag"))
                .isEqualTo("https://api.clockify.me/api");
    }

    @Test
    void explicitDefaultPortIsNormalizedAway() {
        assertThat(ClockifyUrlNormalizer.normalizeBackendApiUrl("https://api.clockify.me:443/api"))
                .isEqualTo("https://api.clockify.me/api");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.clockify.me:8080/api",
            "https://api.clockify.me:4444/api",
            "https://api.clockify.me:80/api"
    })
    void rejectsNonStandardHttpsPort(String raw) {
        assertThatThrownBy(() -> ClockifyUrlNormalizer.normalizeBackendApiUrl(raw))
                .isInstanceOf(InvalidAddonTokenException.class)
                .hasMessageContaining("port");
    }
}
