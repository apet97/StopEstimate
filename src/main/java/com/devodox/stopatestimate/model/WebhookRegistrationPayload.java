package com.devodox.stopatestimate.model;

public record WebhookRegistrationPayload(
        String path,
        String webhookType,
        String authToken
) {
}
