package com.devodox.stopatestimate.model;

import java.util.List;

public record InstalledPayload(
        String addonId,
        String authToken,
        String workspaceId,
        String asUser,
        String apiUrl,
        String addonUserId,
        List<WebhookRegistrationPayload> webhooks
) {
}
