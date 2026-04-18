package com.devodox.stopatestimate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TestJwtFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final PrivateKey PRIVATE_KEY = loadPrivateKey();
    private static final PrivateKey FOREIGN_PRIVATE_KEY = generateFreshKey();

    private TestJwtFactory() {
    }

    public static String lifecycleToken(String workspaceId) {
        return sign(baseClaims(workspaceId));
    }

    public static String installationToken(String workspaceId) {
        Map<String, Object> claims = baseClaims(workspaceId);
        claims.put("reportsUrl", "https://reports.api.clockify.me");
        claims.put("userId", "owner-user-123");
        return sign(claims);
    }

    public static String userToken(String workspaceId) {
        Map<String, Object> claims = baseClaims(workspaceId);
        claims.put("userId", "user-123");
        claims.put("language", "EN");
        claims.put("theme", "DARK");
        claims.put("reportsUrl", "https://reports.api.clockify.me");
        return sign(claims);
    }

    public static String webhookToken(String workspaceId) {
        return sign(baseClaims(workspaceId));
    }

    public static String webhookToken(String workspaceId, Map<String, Object> extraClaims) {
        Map<String, Object> claims = baseClaims(workspaceId);
        claims.putAll(extraClaims);
        return sign(claims);
    }

    /** Modifiable claim set with every required claim filled in, for negative-path tests. */
    public static Map<String, Object> baseValidClaims(String workspaceId) {
        return baseClaims(workspaceId);
    }

    /** Signs arbitrary claims with the production test key. */
    public static String signClaims(Map<String, Object> claims) {
        return sign(claims);
    }

    /** Signs with a freshly generated foreign RSA key — used to verify signature rejection. */
    public static String signClaimsWithForeignKey(Map<String, Object> claims) {
        return signWithKey(claims, FOREIGN_PRIVATE_KEY);
    }

    private static Map<String, Object> baseClaims(String workspaceId) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "clockify");
        claims.put("type", "addon");
        claims.put("sub", "stop-at-estimate");
        claims.put("workspaceId", workspaceId);
        claims.put("addonId", "addon-123");
        claims.put("backendUrl", "https://api.clockify.me/api");
        claims.put("iat", now);
        claims.put("nbf", now - 1);
        claims.put("exp", now + 3600);
        return claims;
    }

    private static String sign(Map<String, Object> claims) {
        return signWithKey(claims, PRIVATE_KEY);
    }

    private static String signWithKey(Map<String, Object> claims, PrivateKey key) {
        try {
            String headerJson = OBJECT_MAPPER.writeValueAsString(Map.of("alg", "RS256", "typ", "JWT"));
            String payloadJson = OBJECT_MAPPER.writeValueAsString(claims);
            String header = encode(headerJson.getBytes(StandardCharsets.UTF_8));
            String payload = encode(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String signed = encode(signature.sign());
            return signingInput + "." + signed;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign test JWT", e);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static PrivateKey loadPrivateKey() {
        try (InputStream inputStream = TestJwtFactory.class.getResourceAsStream("/test-private-key.pem")) {
            if (inputStream == null) {
                throw new IllegalStateException("test-private-key.pem missing from test resources");
            }
            String pem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read test private key", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load test private key", e);
        }
    }

    private static PrivateKey generateFreshKey() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            return pair.getPrivate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate foreign RSA key", e);
        }
    }
}
