package com.devodox.stopatestimate.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ClockifyUrlNormalizer {

    /**
     * Any subdomain of {@code clockify.me} is accepted — covers {@code api.clockify.me},
     * {@code api.eu.clockify.me}, {@code global.api.clockify.me}, {@code reports.api.clockify.me},
     * regional shards (euc1/apse2/…), etc. Requires at least one subdomain component so bare
     * {@code clockify.me} (marketing site) is rejected. A JWT whose {@code backendUrl}
     * resolves to an attacker-owned host would otherwise receive the installation token via
     * {@code X-Addon-Token} on the next outbound call.
     */
    private static final Pattern ALLOWED_HOST = Pattern.compile("^(?:[a-z0-9-]+\\.)+clockify\\.me$");

    private ClockifyUrlNormalizer() {
    }

    public static String normalizeBackendApiUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidAddonTokenException("backendUrl is missing");
        }

        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new InvalidAddonTokenException("Invalid Clockify backend URL", e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw new InvalidAddonTokenException("Clockify backend URL must use https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidAddonTokenException("Clockify backend URL has no host");
        }
        String hostLower = host.toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOST.matcher(hostLower).matches()) {
            throw new InvalidAddonTokenException("Clockify backend URL host is not permitted");
        }

        // Only accept the implicit https port. A JWT carrying
        // `https://api.clockify.me:4444/api` would otherwise be honored verbatim and let an
        // attacker who can craft a JWT point the addon at a non-production port behind the
        // same hostname (e.g. a staging tier bound to an odd port).
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new InvalidAddonTokenException("Clockify backend URL port is not permitted");
        }

        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        if (path.isBlank() || "/".equals(path)) {
            path = "/api";
        } else if (path.matches(".*/api/v\\d+$")) {
            path = path.replaceAll("/api/v\\d+$", "/api");
        } else if (!path.endsWith("/api")) {
            path = path + "/api";
        }

        // SEC-05: drop userInfo, query, and fragment from the normalized URL. A crafted
        // JWT backendUrl like `https://user:pass@api.clockify.me/api?inject=true#frag`
        // would otherwise propagate embedded credentials, arbitrary query params, and a
        // fragment into every outbound Clockify call. With the port check above we can hard-code
        // -1 so the rebuilt URI always uses the default https port.
        try {
            return new URI("https", null, hostLower, -1, path, null, null).toString();
        } catch (URISyntaxException e) {
            throw new InvalidAddonTokenException("Failed to rebuild Clockify backend URL", e);
        }
    }
}
