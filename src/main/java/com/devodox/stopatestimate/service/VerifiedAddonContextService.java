package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.model.VerifiedAddonContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class VerifiedAddonContextService {

    private final TokenVerificationService tokenVerificationService;

    public VerifiedAddonContextService(TokenVerificationService tokenVerificationService) {
        this.tokenVerificationService = tokenVerificationService;
    }

    public VerifiedAddonContext verifyRequired(String token) {
        return fromClaims(tokenVerificationService.verifyAndParseClaims(token));
    }

    public VerifiedAddonContext fromClaims(Map<String, Object> claims) {
        Map<String, Object> copy = new LinkedHashMap<>(claims);
        return new VerifiedAddonContext(
                asString(copy.get("workspaceId")),
                asString(copy.get("addonId")),
                asString(copy.get("userId")),
                asString(copy.get("backendUrl")),
                asString(copy.get("reportsUrl")),
                defaultString(asString(copy.get("language")), "en"),
                defaultString(asString(copy.get("theme")), "DEFAULT"),
                Map.copyOf(copy)
        );
    }

    private static String asString(Object value) {
        return value instanceof String string ? string : null;
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
