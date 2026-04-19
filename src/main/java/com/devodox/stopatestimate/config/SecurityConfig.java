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
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

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
    private static final Set<String> FORBIDDEN_SALTS = Set.of(
            "0123456789abcdef0123456789abcdef");

    /**
     * Placeholder DB passwords shipped in repo examples / docker-compose.yml. Operators
     * MUST override SPRING_DATASOURCE_PASSWORD in any deploy — startup fails if the
     * effective password is blank or matches one of these checked-in values.
     */
    private static final Set<String> FORBIDDEN_DB_PASSWORDS = Set.of("stop_at_estimate");

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
                            + "generate a unique salt with `openssl rand -hex 16`");
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
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }

    @Bean
    public TextEncryptor textEncryptor() {
        // SEC-03: Encryptors.text is AES-256-CBC with a FIXED salt, which means the same
        // plaintext always encrypts to the same ciphertext — an attacker with DB read
        // access could detect duplicate installation/webhook tokens across workspaces
        // without needing to break the cipher. Encryptors.delux is the non-deterministic
        // TextEncryptor (random 16-byte IV per value) and is the right upgrade path.
        // (Encryptors.stronger is GCM but returns a BytesEncryptor, not a TextEncryptor.)
        //
        // Existing DB rows are still in the legacy deterministic format. Read them
        // transparently via a fallback decrypt so no one-shot migration is required for
        // the switchover; new writes land in the non-deterministic format. Follow-up: a
        // background re-encrypt job can convert legacy rows and we can drop the fallback.
        TextEncryptor modern = Encryptors.delux(
                addonProperties.getEncryptionKeyHex(), addonProperties.getEncryptionSaltHex());
        TextEncryptor legacy = Encryptors.text(
                addonProperties.getEncryptionKeyHex(), addonProperties.getEncryptionSaltHex());
        return new TextEncryptor() {
            @Override
            public String encrypt(String text) {
                return modern.encrypt(text);
            }

            @Override
            public String decrypt(String encryptedText) {
                try {
                    return modern.decrypt(encryptedText);
                } catch (RuntimeException modernFailure) {
                    // Not in the new format; must be a pre-SEC-03 deterministic-CBC value.
                    return legacy.decrypt(encryptedText);
                }
            }
        };
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
                                "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/lifecycle/**", "/webhook/**").permitAll()
                        // /api/** tokens are validated inside the handler via
                        // VerifiedAddonContextService.verifyRequired — Spring Security is left
                        // permissive here so the token-missing case is mapped to 401 by the
                        // GlobalExceptionHandler rather than a Spring auth failure.
                        .requestMatchers("/api/**").permitAll()
                        .anyRequest().permitAll()
                )
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                        .contentTypeOptions(opts -> {})
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                // Sidebar renders inside Clockify's iframe; frame-ancestors pins the
                                // parent origin. style-src 'unsafe-inline' covers the inline <style>
                                // block in sidebar.html (P2 TODO: move to external CSS).
                                "default-src 'self'; "
                                        + "frame-ancestors https://*.clockify.me; "
                                        + "img-src 'self' data:; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "script-src 'self'; "
                                        + "connect-src 'self' https://*.clockify.me"))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(rp -> rp.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                );
        return http.build();
    }
}
