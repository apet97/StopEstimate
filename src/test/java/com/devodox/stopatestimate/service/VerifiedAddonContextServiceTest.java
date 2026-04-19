package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.model.VerifiedAddonContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * TEST-10: covers {@link VerifiedAddonContextService#fromClaims(Map)} — default fallbacks,
 * null vs. blank handling, non-string values, and the immutable claims copy.
 */
class VerifiedAddonContextServiceTest {

    private TokenVerificationService tokenVerificationService;
    private VerifiedAddonContextService service;

    @BeforeEach
    void setUp() {
        tokenVerificationService = Mockito.mock(TokenVerificationService.class);
        service = new VerifiedAddonContextService(tokenVerificationService);
    }

    @Test
    void allClaimsPresentPopulatesRecord() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("workspaceId", "ws-1");
        claims.put("addonId", "addon-1");
        claims.put("userId", "user-1");
        claims.put("backendUrl", "https://api.clockify.me/api");
        claims.put("reportsUrl", "https://reports.api.clockify.me");
        claims.put("language", "sr");
        claims.put("theme", "DARK");

        VerifiedAddonContext ctx = service.fromClaims(claims);

        assertThat(ctx.workspaceId()).isEqualTo("ws-1");
        assertThat(ctx.addonId()).isEqualTo("addon-1");
        assertThat(ctx.userId()).isEqualTo("user-1");
        assertThat(ctx.backendUrl()).isEqualTo("https://api.clockify.me/api");
        assertThat(ctx.reportsUrl()).isEqualTo("https://reports.api.clockify.me");
        assertThat(ctx.language()).isEqualTo("sr");
        assertThat(ctx.theme()).isEqualTo("DARK");
        assertThat(ctx.claims()).containsAllEntriesOf(claims);
    }

    @Test
    void missingLanguageDefaultsToEn() {
        VerifiedAddonContext ctx = service.fromClaims(Map.of("workspaceId", "ws-1"));
        assertThat(ctx.language()).isEqualTo("en");
    }

    @Test
    void blankLanguageDefaultsToEn() {
        VerifiedAddonContext ctx = service.fromClaims(Map.of("workspaceId", "ws-1", "language", "   "));
        assertThat(ctx.language()).isEqualTo("en");
    }

    @Test
    void missingThemeDefaultsToDefault() {
        VerifiedAddonContext ctx = service.fromClaims(Map.of("workspaceId", "ws-1"));
        assertThat(ctx.theme()).isEqualTo("DEFAULT");
    }

    @Test
    void blankThemeDefaultsToDefault() {
        VerifiedAddonContext ctx = service.fromClaims(Map.of("workspaceId", "ws-1", "theme", ""));
        assertThat(ctx.theme()).isEqualTo("DEFAULT");
    }

    @Test
    void missingUserIdYieldsNullWithoutException() {
        VerifiedAddonContext ctx = service.fromClaims(Map.of("workspaceId", "ws-1"));
        assertThat(ctx.userId()).isNull();
    }

    @Test
    void nonStringClaimValueMapsToNull() {
        // Numeric workspaceId — the asString helper returns null for non-String, so the field
        // must be null rather than throwing a ClassCastException.
        Map<String, Object> claims = Map.of("workspaceId", 12345);
        VerifiedAddonContext ctx = service.fromClaims(claims);
        assertThat(ctx.workspaceId()).isNull();
    }

    @Test
    void claimsMapIsImmutableCopy() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("workspaceId", "ws-1");
        VerifiedAddonContext ctx = service.fromClaims(source);

        source.put("mutated", "value");

        // Mutating the source after construction must not leak into the record's claims copy.
        assertThat(ctx.claims()).doesNotContainKey("mutated");
    }

    @Test
    void verifyRequiredDelegatesToTokenVerificationService() {
        when(tokenVerificationService.verifyAndParseClaims("tok"))
                .thenReturn(Map.of("workspaceId", "ws-1"));

        VerifiedAddonContext ctx = service.verifyRequired("tok");

        assertThat(ctx.workspaceId()).isEqualTo("ws-1");
    }
}
