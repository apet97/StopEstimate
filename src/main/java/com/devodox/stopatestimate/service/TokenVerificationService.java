package com.devodox.stopatestimate.service;

import com.devodox.stopatestimate.config.AddonProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TokenVerificationService {

    /** Tolerate small clock drift between Clockify's issuer and this host. */
    private static final long CLOCK_SKEW_SECONDS = 60;

    /**
     * Upper bound on how stale {@code iat} may be. Installation tokens may be longer-lived, but
     * anything older than a day is almost certainly a replay.
     */
    private static final long MAX_IAT_AGE_SECONDS = 24 * 60 * 60;

    private final ObjectMapper objectMapper;
    private final AddonProperties addonProperties;
    private final RSAPublicKey publicKey;
    private final Clock clock;

    public TokenVerificationService(ObjectMapper objectMapper, AddonProperties addonProperties, Clock clock) {
        this.objectMapper = objectMapper;
        this.addonProperties = addonProperties;
        this.clock = clock;
        this.publicKey = loadPublicKey();
    }

    public Map<String, Object> verifyAndParseClaims(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            throw new InvalidAddonTokenException("Missing Clockify token");
        }

        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new InvalidAddonTokenException("Clockify token must contain three JWT parts");
        }

        Map<String, Object> header = readJson(base64UrlDecode(parts[0]));
        Object alg = header.get("alg");
        if (!"RS256".equals(alg)) {
            throw new InvalidAddonTokenException("Clockify token must use RS256");
        }

        verifySignature(parts[0] + "." + parts[1], parts[2]);

        Map<String, Object> claims = normalizeClaims(readJson(base64UrlDecode(parts[1])));
        assertClaim(claims, "iss", "clockify");
        assertClaim(claims, "type", "addon");
        assertClaim(claims, "sub", addonProperties.getKey());

        long nowEpochSeconds = clock.instant().getEpochSecond();

        long exp = requireNumericSeconds(claims, "exp");
        if (nowEpochSeconds > exp + CLOCK_SKEW_SECONDS) {
            throw new InvalidAddonTokenException("Clockify token has expired");
        }

        Long nbf = optionalNumericSeconds(claims, "nbf");
        if (nbf != null && nowEpochSeconds + CLOCK_SKEW_SECONDS < nbf) {
            throw new InvalidAddonTokenException("Clockify token not yet valid");
        }

        Long iat = optionalNumericSeconds(claims, "iat");
        if (iat != null) {
            if (iat > nowEpochSeconds + CLOCK_SKEW_SECONDS) {
                throw new InvalidAddonTokenException("Clockify token iat is in the future");
            }
            if (nowEpochSeconds - iat > MAX_IAT_AGE_SECONDS) {
                throw new InvalidAddonTokenException("Clockify token iat is too old");
            }
        }

        return claims;
    }

    private void verifySignature(String signedContent, String signaturePart) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signedContent.getBytes(StandardCharsets.UTF_8));
            boolean valid = signature.verify(Base64.getUrlDecoder().decode(signaturePart));
            if (!valid) {
                throw new InvalidAddonTokenException("Clockify token signature verification failed");
            }
        } catch (InvalidAddonTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidAddonTokenException("Clockify token signature verification failed", e);
        }
    }

    private Map<String, Object> readJson(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, new TypeReference<>() {});
        } catch (IOException e) {
            throw new InvalidAddonTokenException("Clockify token JSON payload could not be parsed", e);
        }
    }

    private Map<String, Object> normalizeClaims(Map<String, Object> claims) {
        Map<String, Object> normalized = new LinkedHashMap<>(claims);

        if (!normalized.containsKey("workspaceId") && normalized.containsKey("activeWs")) {
            normalized.put("workspaceId", normalized.get("activeWs"));
        }
        if (!normalized.containsKey("backendUrl")) {
            Object backendUrl = normalized.getOrDefault("apiUrl",
                    normalized.getOrDefault("baseURL", normalized.get("baseUrl")));
            if (backendUrl != null) {
                normalized.put("backendUrl", backendUrl);
            }
        }
        if (!normalized.containsKey("userId") && normalized.containsKey("user")) {
            normalized.put("userId", normalized.get("user"));
        }
        Object backendUrl = normalized.get("backendUrl");
        if (backendUrl instanceof String url && !url.isBlank()) {
            normalized.put("backendUrl", ClockifyUrlNormalizer.normalizeBackendApiUrl(url));
        }
        return Map.copyOf(normalized);
    }

    private void assertClaim(Map<String, Object> claims, String key, String expected) {
        Object actual = claims.get(key);
        if (!expected.equals(actual)) {
            throw new InvalidAddonTokenException("Clockify token claim %s did not match expected value".formatted(key));
        }
    }

    private long requireNumericSeconds(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (!(value instanceof Number number)) {
            throw new InvalidAddonTokenException("Clockify token claim " + key + " must be a number");
        }
        return number.longValue();
    }

    private Long optionalNumericSeconds(Map<String, Object> claims, String key) {
        Object value = claims.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new InvalidAddonTokenException("Clockify token claim " + key + " must be a number");
        }
        return number.longValue();
    }

    private byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private RSAPublicKey loadPublicKey() {
        try (InputStream inputStream = getClass().getResourceAsStream("/clockify-public-key.pem")) {
            if (inputStream == null) {
                throw new IllegalStateException("clockify-public-key.pem not found on the classpath");
            }
            String pem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            return (RSAPublicKey) key;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load Clockify public key", e);
        }
    }
}
