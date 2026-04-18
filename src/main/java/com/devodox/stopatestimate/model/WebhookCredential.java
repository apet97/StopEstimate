package com.devodox.stopatestimate.model;

import java.io.Serializable;

public record WebhookCredential(String eventType, String authToken) implements Serializable {
}
