package com.devodox.stopatestimate.model;

public record SettingValuePayload(
        String id,
        String name,
        Object value
) {
}
