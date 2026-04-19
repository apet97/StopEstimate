package com.devodox.stopatestimate.controller;

import com.devodox.stopatestimate.service.ClockifyLifecycleService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/lifecycle", consumes = MediaType.APPLICATION_JSON_VALUE)
public class LifecycleController {

    private final ClockifyLifecycleService lifecycleService;

    public LifecycleController(ClockifyLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @PostMapping("/installed")
    public ResponseEntity<Void> installed(
            @RequestBody String body,
            @RequestHeader(value = "X-Addon-Lifecycle-Token", required = true) String lifecycleToken) {
        lifecycleService.handleInstalled(body, lifecycleToken);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/deleted")
    public ResponseEntity<Void> deleted(
            @RequestBody String body,
            @RequestHeader(value = "X-Addon-Lifecycle-Token", required = true) String lifecycleToken) {
        lifecycleService.handleDeleted(body, lifecycleToken);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/status-changed")
    public ResponseEntity<Void> statusChanged(
            @RequestBody String body,
            @RequestHeader(value = "X-Addon-Lifecycle-Token", required = true) String lifecycleToken) {
        lifecycleService.handleStatusChanged(body, lifecycleToken);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/settings-updated")
    public ResponseEntity<Void> settingsUpdated(
            @RequestBody String body,
            @RequestHeader(value = "X-Addon-Lifecycle-Token", required = true) String lifecycleToken) {
        lifecycleService.handleSettingsUpdated(body, lifecycleToken);
        return ResponseEntity.ok().build();
    }
}
