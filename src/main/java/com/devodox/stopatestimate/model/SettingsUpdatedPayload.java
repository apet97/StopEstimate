package com.devodox.stopatestimate.model;

import java.util.List;

public record SettingsUpdatedPayload(
        String workspaceId,
        String addonId,
        List<SettingValuePayload> settings
) {
}
