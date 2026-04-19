package com.devodox.stopatestimate.api;

public final class ClockifyApiUrls {
    private ClockifyApiUrls() {
    }

    public static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("/+$", "");
    }

    public static String join(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Clockify baseUrl must not be null or blank");
        }
        String normalizedBase = trimTrailingSlash(baseUrl);
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return normalizedBase + normalizedPath;
    }
}
