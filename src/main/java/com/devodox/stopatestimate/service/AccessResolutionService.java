package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.api.ClockifyBackendApiClient;
import com.devodox.stopatestimate.model.InstallationRecord;
import com.devodox.stopatestimate.model.ProjectMemberAccess;
import com.devodox.stopatestimate.model.ProjectState;
import com.devodox.stopatestimate.model.RateInfo;
import com.devodox.stopatestimate.util.ClockifyJson;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AccessResolutionService {
    private final ClockifyBackendApiClient backendApiClient;

    public AccessResolutionService(ClockifyBackendApiClient backendApiClient) {
        this.backendApiClient = backendApiClient;
    }

    public List<ProjectMemberAccess> resolveAllowedMembers(InstallationRecord installation, ProjectState projectState) {
        Map<String, ProjectMemberAccess> allowed = new LinkedHashMap<>();
        Map<String, ProjectMemberAccess> currentMembers = new HashMap<>();
        for (ProjectMemberAccess currentMember : projectState.directMembers()) {
            currentMembers.put(currentMember.userId(), currentMember);
        }

        includeUsers(allowed, backendApiClient.filterUsers(
                installation,
                List.of("OWNER", "WORKSPACE_ADMIN"),
                null,
                "NONE"), currentMembers);
        includeUsers(allowed, backendApiClient.filterUsers(
                installation,
                List.of("PROJECT_MANAGER"),
                projectState.projectId(),
                "PROJECT"), currentMembers);

        return List.copyOf(allowed.values());
    }

    private void includeUsers(
            Map<String, ProjectMemberAccess> allowed,
            List<JsonObject> users,
            Map<String, ProjectMemberAccess> currentMembers) {
        for (JsonObject user : users) {
            String userId = ClockifyJson.string(user, "id").orElse(null);
            if (userId == null) {
                continue;
            }
            allowed.putIfAbsent(userId, currentMembers.getOrDefault(userId, new ProjectMemberAccess(
                    userId,
                    RateInfo.empty(),
                    RateInfo.empty(),
                    "PROJECT",
                    "ACTIVE")));
        }
    }
}
