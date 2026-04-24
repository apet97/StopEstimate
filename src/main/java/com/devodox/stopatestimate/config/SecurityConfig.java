package com.devodox.stopatestimate.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import java.util.Set;
import java.util.regex.Pattern;

@Configuration
public class SecurityConfig {

    private static final Pattern HEX_PATTERN = Pattern.compile("[0-9a-fA-F]+");

    /**
     * Hex values that ship in repo examples and must never be used in any deploy
     * (including local dev). Operators set their own via env.
     */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    // 64-char hex example; shorter values are already rejected by the length check.
    private static final Set<String> FORBIDDEN_SALTS = Set.of(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

    /**
     * Placeholder DB passwords shipped in repo examples / docker-compose.yml. Operators
     * MUST override SPRING_DATASOURCE_PASSWORD in any deploy — startup fails if the
     * effective password is blank or matches one of these checked-in values.
     */
    private static final Set<String> FORBIDDEN_DB_PASSWORDS = Set.of("stop_at_estimate");

    /** Placeholder base-URLs shipped in repo examples that must never reach a real deploy. */
    private static final Set<String> FORBIDDEN_BASE_URLS = Set.of(
            "https://example.ngrok-free.app",
            "https://your-https-url",
            "https://YOUR-HTTPS-URL");

    private final AddonProperties addonProperties;
    private final String datasourcePassword;

    public SecurityConfig(
            AddonProperties addonProperties,
            @Value("${spring.datasource.password:}") String datasourcePassword) {
        this.addonProperties = addonProperties;
        this.datasourcePassword = datasourcePassword;
    }

    @PostConstruct
    void validateEncryptionConfig() {
        String key = normalize(addonProperties.getEncryptionKeyHex());
        String salt = normalize(addonProperties.getEncryptionSaltHex());

        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "addon.encryption-key-hex (APP_ENCRYPTION_KEY_HEX) must be set; no default is accepted");
        }
        if (salt == null || salt.isBlank()) {
            throw new IllegalStateException(
                    "addon.encryption-salt-hex (APP_ENCRYPTION_SALT_HEX) must be set; no default is accepted");
        }
        if (key.length() < 64) {
            throw new IllegalStateException(
                    "addon.encryption-key-hex must be a hex string of at least 64 characters");
        }
        if (!HEX_PATTERN.matcher(key).matches()) {
            throw new IllegalStateException(
                    "addon.encryption-key-hex must contain only hex characters (0-9, a-f, A-F)");
        }
        if (salt.length() < 64) {
            throw new IllegalStateException(
                    "addon.encryption-salt-hex must be a hex string of at least 64 characters "
                            + "(256 bits, matching the encryption key length)");
        }
        if (!HEX_PATTERN.matcher(salt).matches()) {
            throw new IllegalStateException(
                    "addon.encryption-salt-hex must contain only hex characters (0-9, a-f, A-F)");
        }
        if (FORBIDDEN_KEYS.contains(key)) {
            throw new IllegalStateException(
                    "addon.encryption-key-hex is set to a checked-in example value; "
                            + "generate a unique key with `openssl rand -hex 32`");
        }
        if (FORBIDDEN_SALTS.contains(salt)) {
            throw new IllegalStateException(
                    "addon.encryption-salt-hex is set to a checked-in example value; "
                            + "generate a unique salt with `openssl rand -hex 32`");
        }

        if (datasourcePassword == null || datasourcePassword.isBlank()) {
            throw new IllegalStateException(
                    "spring.datasource.password (SPRING_DATASOURCE_PASSWORD) must be set; "
                            + "no default is accepted");
        }
        if (FORBIDDEN_DB_PASSWORDS.contains(datasourcePassword)) {
            throw new IllegalStateException(
                    "spring.datasource.password is set to a checked-in example value; "
                            + "set SPRING_DATASOURCE_PASSWORD to a deploy-specific secret");
        }

        validateBaseUrl(addonProperties.getBaseUrl());
    }

    private static void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "addon.base-url (ADDON_BASE_URL) must be set to the public HTTPS URL this "
                            + "deploy is reachable at; no default is accepted");
        }
        String trimmed = baseUrl.trim();
        if (FORBIDDEN_BASE_URLS.contains(trimmed)) {
            throw new IllegalStateException(
                    "addon.base-url is set to a checked-in placeholder (" + trimmed + "); "
                            + "set ADDON_BASE_URL to the deploy's real public HTTPS URL");
        }
        if (!trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException(
                    "addon.base-url must be an HTTPS URL; Clockify requires TLS for addon "
                            + "endpoints (got: " + trimmed + ")");
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    /**
     * Empty user store. The addon never authenticates via username/password; all requests are
     * gated by {@code VerifiedAddonContextService} using Clockify-issued JWTs. Defining this bean
     * suppresses Spring Boot's {@code UserDetailsServiceAutoConfiguration}, which would otherwise
     * generate a random password at startup and log "Using generated security password" — noise
     * that implies a login surface that does not exist.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    @Bean
    public TextEncryptor textEncryptor() {
        // SEC-03: non-deterministic AES-CBC (random 16-byte IV per value). Drain
        // complete; legacy deterministic-CBC fallback removed in PR #6.
        return Encryptors.delux(
                addonProperties.getEncryptionKeyHex(),
                addonProperties.getEncryptionSaltHex());
    }

    /**
     * Stateless, token-based add-on. Auth is enforced at the service layer via
     * {@code VerifiedAddonContextService} / {@code TokenVerificationService}; this chain only
     * sets transport-level headers (CSP for iframe embedding in Clockify, HSTS, clickjacking /
     * MIME-sniffing protections) and disables the session/CSRF machinery that would reject
     * legitimate addon POSTs.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET,
                                "/manifest",
                                "/sidebar",
                                "/sidebar/**",
                                "/static/**",
                                "/css/**",
                                "/js/**",
                                "/actuator/health",
                                // B2: expose k8s-style liveness + readiness probes
                                // (/actuator/health/liveness, /actuator/health/readiness).
                                "/actuator/health/**",
                                // B1: Prometheus scrape. Alongside /actuator/health this is the
                                // minimal surface operators need; anything else on /actuator/**
                                // stays locked by the denyAll below.
                                "/actuator/prometheus",
                                "/actuator/info").permitAll()
                        // Any other actuator endpoint is denied. Spring Security uses first-match,
                        // so the explicit permits above still pass. This keeps accidental future
                        // exposures (env, beans, mappings, heapdump, etc.) locked even if the yml
                        // exposure list grows.
                        .requestMatchers("/actuator/**").denyAll()
                        .requestMatchers(HttpMethod.POST, "/lifecycle/**", "/webhook/**").permitAll()
                        // /api/** tokens are validated inside the handler via
                        // VerifiedAddonContextService.verifyRequired — Spring Security is left
                        // permissive here so the token-missing case is mapped to 401 by the
                        // GlobalExceptionHandler rather than a Spring auth failure.
                        .requestMatchers("/api/**").permitAll()
                        .anyRequest().permitAll()
                )
                .headers(headers -> headers
                        // Frame-ancestors in the CSP below is authoritative for iframe embedding.
                        // Leaving X-Frame-Options in place would block Firefox, which applies both
                        // headers with AND logic — SAMEORIGIN rejects Clockify's origin.
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
                        .contentTypeOptions(opts -> {})
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                // Sidebar renders inside Clockify's iframe; frame-ancestors pins the
                                // parent origin. All sidebar styles live in /css/sidebar.css so
                                // style-src can stay strict ('self' only).
                                "default-src 'self'; "
                                        + "frame-ancestors https://*.clockify.me; "
                                        + "img-src 'self' data:; "
                                        + "style-src 'self'; "
                                        + "script-src 'self'; "
                                        + "connect-src 'self' https://*.clockify.me"))
                        .httpStrictTransportSecurity(hsts -> hsts
                                // Production runs behind a TLS-terminating proxy (cloudflared /
                                // Clockify edge), so the embedded Tomcat sees http. The default
                                // HSTS matcher only emits over https, which means the header would
                                // silently never ship. Force emission on every request — the proxy
                                // guarantees TLS to the browser.
                                .requestMatcher(AnyRequestMatcher.INSTANCE)
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                );
        return http.build();
    }
}
