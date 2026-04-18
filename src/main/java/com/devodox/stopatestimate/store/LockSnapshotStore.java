package com.devodox.stopatestimate.store;

import com.devodox.stopatestimate.model.ProjectLockSnapshot;
import com.devodox.stopatestimate.model.ProjectMemberAccess;
import com.devodox.stopatestimate.model.RateInfo;
import com.devodox.stopatestimate.model.entity.ProjectLockSnapshotEntity;
import com.devodox.stopatestimate.repository.ProjectLockSnapshotRepository;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class LockSnapshotStore {

    private static final Gson GSON = new Gson();

    private final ProjectLockSnapshotRepository repository;

    public LockSnapshotStore(ProjectLockSnapshotRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void save(ProjectLockSnapshot snapshot) {
        ProjectLockSnapshotEntity.Key key = new ProjectLockSnapshotEntity.Key(
                snapshot.workspaceId(), snapshot.projectId());
        ProjectLockSnapshotEntity entity = repository.findById(key).orElseGet(ProjectLockSnapshotEntity::new);
        entity.setId(key);
        entity.setOriginalIsPublic(snapshot.originalPublic());
        entity.setOriginalMembershipsJson(serializeMembers(snapshot.originalMembers()));
        entity.setOriginalUserGroupsJson(serializeUserGroups(snapshot.originalUserGroupIds()));
        entity.setLockReason(snapshot.reason());
        entity.setLockedAt(snapshot.lockedAt() == null ? Instant.now() : snapshot.lockedAt());
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public Optional<ProjectLockSnapshot> findByProject(String workspaceId, String projectId) {
        return repository.findById(new ProjectLockSnapshotEntity.Key(workspaceId, projectId))
                .map(this::toRecord);
    }

    @Transactional(readOnly = true)
    public List<ProjectLockSnapshot> findAllSnapshots() {
        List<ProjectLockSnapshot> result = new ArrayList<>();
        for (ProjectLockSnapshotEntity entity : repository.findAll()) {
            result.add(toRecord(entity));
        }
        return result;
    }

    @Transactional
    public void deleteByProject(String workspaceId, String projectId) {
        repository.deleteById(new ProjectLockSnapshotEntity.Key(workspaceId, projectId));
    }

    @Transactional(readOnly = true)
    public List<ProjectLockSnapshot> findByWorkspaceId(String workspaceId) {
        return repository.findAllByIdWorkspaceId(workspaceId).stream().map(this::toRecord).toList();
    }

    @Transactional
    public void deleteByWorkspaceId(String workspaceId) {
        repository.deleteAllByIdWorkspaceId(workspaceId);
    }

    @Transactional
    public void deleteAllSnapshots() {
        repository.deleteAll();
    }

    private ProjectLockSnapshot toRecord(ProjectLockSnapshotEntity entity) {
        return new ProjectLockSnapshot(
                entity.getId().getWorkspaceId(),
                entity.getId().getProjectId(),
                entity.isOriginalIsPublic(),
                deserializeMembers(entity.getOriginalMembershipsJson()),
                deserializeUserGroups(entity.getOriginalUserGroupsJson()),
                entity.getLockReason(),
                entity.getLockedAt());
    }

    private static String serializeMembers(List<ProjectMemberAccess> members) {
        if (members == null) {
            return "[]";
        }
        JsonArray array = new JsonArray();
        for (ProjectMemberAccess member : members) {
            JsonObject m = new JsonObject();
            m.addProperty("userId", member.userId());
            m.addProperty("membershipType", member.membershipType());
            m.addProperty("membershipStatus", member.membershipStatus());
            m.add("hourlyRate", rateToJson(member.hourlyRate()));
            m.add("costRate", rateToJson(member.costRate()));
            array.add(m);
        }
        return array.toString();
    }

    private static List<ProjectMemberAccess> deserializeMembers(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonArray()) {
            return List.of();
        }
        List<ProjectMemberAccess> out = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject o = entry.getAsJsonObject();
            out.add(new ProjectMemberAccess(
                    o.has("userId") && !o.get("userId").isJsonNull() ? o.get("userId").getAsString() : null,
                    rateFromJson(o.get("hourlyRate")),
                    rateFromJson(o.get("costRate")),
                    o.has("membershipType") && !o.get("membershipType").isJsonNull() ? o.get("membershipType").getAsString() : null,
                    o.has("membershipStatus") && !o.get("membershipStatus").isJsonNull() ? o.get("membershipStatus").getAsString() : null));
        }
        return out;
    }

    private static JsonElement rateToJson(RateInfo rate) {
        if (rate == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("amount", rate.amount() == null ? null : rate.amount().toPlainString());
        obj.addProperty("currency", rate.currency());
        return obj;
    }

    private static RateInfo rateFromJson(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        JsonObject o = element.getAsJsonObject();
        BigDecimal amount = o.has("amount") && !o.get("amount").isJsonNull()
                ? new BigDecimal(o.get("amount").getAsString())
                : null;
        String currency = o.has("currency") && !o.get("currency").isJsonNull()
                ? o.get("currency").getAsString()
                : null;
        return new RateInfo(amount, currency);
    }

    private static String serializeUserGroups(List<String> ids) {
        return GSON.toJson(ids == null ? List.of() : ids);
    }

    private static List<String> deserializeUserGroups(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry.isJsonNull()) {
                continue;
            }
            out.add(entry.getAsString());
        }
        return out;
    }
}
