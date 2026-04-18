package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.model.ProjectGuardSummary;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClockifyCutoffService {
    private final EstimateGuardService estimateGuardService;

    public ClockifyCutoffService(EstimateGuardService estimateGuardService) {
        this.estimateGuardService = estimateGuardService;
    }

    public void reconcileAll(String source) {
        estimateGuardService.reconcileAll(source);
    }

    public void reconcileKnownProjects(String workspaceId, String source) {
        estimateGuardService.reconcileKnownProjects(workspaceId, source);
    }

    public void reconcileProject(String workspaceId, String projectId, String source, JsonObject payload) {
        estimateGuardService.reconcileProject(workspaceId, projectId, source, payload);
    }

    public void processDueJobs(String source) {
        estimateGuardService.processDueJobs(source);
    }

    public void cancelWorkspaceJobs(String workspaceId) {
        estimateGuardService.cancelWorkspaceJobs(workspaceId);
    }

    public List<ProjectGuardSummary> listProjectSummaries(String workspaceId) {
        return estimateGuardService.listProjectSummaries(workspaceId);
    }
}
