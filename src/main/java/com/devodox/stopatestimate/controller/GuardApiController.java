package com.devodox.stopatestimate.controller;

import com.devodox.stopatestimate.model.ProjectGuardSummary;
import com.devodox.stopatestimate.model.VerifiedAddonContext;
import com.devodox.stopatestimate.repository.GuardEventRepository;
import com.devodox.stopatestimate.repository.GuardEventRepository.GuardEventView;
import com.devodox.stopatestimate.service.EstimateGuardService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/guard")
@Validated
public class GuardApiController {

    private final EstimateGuardService estimateGuardService;
    private final GuardEventRepository guardEventRepository;

    public GuardApiController(EstimateGuardService estimateGuardService,
                              GuardEventRepository guardEventRepository) {
        this.estimateGuardService = estimateGuardService;
        this.guardEventRepository = guardEventRepository;
    }

    @GetMapping("/projects")
    public ResponseEntity<Map<String, Object>> projects(VerifiedAddonContext context) {
        List<ProjectGuardSummary> projects = estimateGuardService.listProjectSummaries(context.workspaceId());
        return ResponseEntity.ok(Map.of(
                "workspaceId", context.workspaceId(),
                "projects", projects
        ));
    }

    @PostMapping("/reconcile")
    public ResponseEntity<Map<String, Object>> reconcile(VerifiedAddonContext context) {
        estimateGuardService.reconcileKnownProjects(context.workspaceId(), "api:manual");
        List<ProjectGuardSummary> projects = estimateGuardService.listProjectSummaries(context.workspaceId());
        return ResponseEntity.ok(Map.of(
                "workspaceId", context.workspaceId(),
                "reconciled", true,
                "projects", projects
        ));
    }

    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> events(
            VerifiedAddonContext context,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(required = false) String projectId) {
        List<GuardEventView> events = guardEventRepository.findRecent(
                context.workspaceId(),
                projectId,
                PageRequest.of(0, limit));
        return ResponseEntity.ok(Map.of(
                "workspaceId", context.workspaceId(),
                "events", events
        ));
    }
}
