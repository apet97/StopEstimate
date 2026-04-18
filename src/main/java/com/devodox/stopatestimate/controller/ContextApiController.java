package com.devodox.stopatestimate.controller;

import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.VerifiedAddonContext;
import com.devodox.stopatestimate.service.ClockifyLifecycleService;
import com.devodox.stopatestimate.service.VerifiedAddonContextService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ContextApiController {

    private final VerifiedAddonContextService verifiedAddonContextService;
    private final ClockifyLifecycleService lifecycleService;

    public ContextApiController(VerifiedAddonContextService verifiedAddonContextService,
                                ClockifyLifecycleService lifecycleService) {
        this.verifiedAddonContextService = verifiedAddonContextService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> context(
            @RequestHeader(value = "X-Addon-Token", required = false) String token) {
        VerifiedAddonContext context = verifiedAddonContextService.verifyRequired(token);
        InstallationRecord installation = lifecycleService.findInstallation(context.workspaceId()).orElse(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspaceId", context.workspaceId());
        payload.put("addonId", context.addonId());
        payload.put("userId", context.userId());
        payload.put("backendUrl", context.backendUrl());
        payload.put("reportsUrl", context.reportsUrl());
        payload.put("language", context.language());
        payload.put("theme", context.theme());
        payload.put("installed", installation != null);
        payload.put("status", installation == null ? "NOT_INSTALLED" : installation.status().name());
        payload.put("enabled", installation != null && installation.enabled());
        payload.put("defaultResetCadence", installation == null ? "NONE" : installation.defaultResetCadenceValue());
        return ResponseEntity.ok(payload);
    }
}
