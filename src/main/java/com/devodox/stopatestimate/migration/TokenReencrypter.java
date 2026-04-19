package com.devodox.stopatestimate.migration;

import com.devodox.stopatestimate.config.AddonProperties;
import com.devodox.stopatestimate.model.entity.InstallationEntity;
import com.devodox.stopatestimate.model.entity.WebhookRegistrationEntity;
import com.devodox.stopatestimate.repository.InstallationRepository;
import com.devodox.stopatestimate.repository.WebhookRegistrationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SEC-03 drain pass. Finds token columns that are still in the pre-SEC-03 deterministic-CBC format
 * ({@code Encryptors.text}) and rewrites them with the non-deterministic format
 * ({@code Encryptors.delux}) that {@code SecurityConfig.textEncryptor} uses for new writes.
 *
 * <p>Once a deploy confirms zero legacy rows remain (see the {@code legacy_remaining=0} log line
 * emitted by the verification pass), the fallback {@code legacy.decrypt(...)} branch in
 * {@code SecurityConfig.textEncryptor} can be removed in a separate deploy.
 *
 * <p>The component holds both encryptors directly so it can distinguish "already modern"
 * ({@code modern.decrypt} succeeds → skip) from "was legacy" ({@code modern.decrypt} fails, the
 * legacy encryptor succeeds → rewrite). Reusing the wrapping {@code TextEncryptor} bean would
 * hide which branch was taken and force a rewrite of every row on every startup.
 */
@Component
public class TokenReencrypter {

    private final InstallationRepository installationRepository;
    private final WebhookRegistrationRepository webhookRegistrationRepository;
    private final TextEncryptor modern;
    private final TextEncryptor legacy;

    @Autowired
    public TokenReencrypter(
            InstallationRepository installationRepository,
            WebhookRegistrationRepository webhookRegistrationRepository,
            AddonProperties addonProperties) {
        this(installationRepository,
                webhookRegistrationRepository,
                Encryptors.delux(addonProperties.getEncryptionKeyHex(), addonProperties.getEncryptionSaltHex()),
                Encryptors.text(addonProperties.getEncryptionKeyHex(), addonProperties.getEncryptionSaltHex()));
    }

    // Package-private for tests: lets us pass in TextEncryptor stand-ins without constructing the
    // full Encryptors.delux machinery (which requires valid 64-char hex key+salt).
    TokenReencrypter(
            InstallationRepository installationRepository,
            WebhookRegistrationRepository webhookRegistrationRepository,
            TextEncryptor modern,
            TextEncryptor legacy) {
        this.installationRepository = installationRepository;
        this.webhookRegistrationRepository = webhookRegistrationRepository;
        this.modern = modern;
        this.legacy = legacy;
    }

    @Transactional
    public ReencryptReport run() {
        int installationsScanned = 0;
        int installationsConverted = 0;
        for (InstallationEntity entity : installationRepository.findAll()) {
            installationsScanned++;
            String cipher = entity.getInstallationTokenEnc();
            if (cipher == null || cipher.isBlank()) {
                continue;
            }
            String rewritten = reencryptIfLegacy(cipher, "installation", entity.getWorkspaceId());
            if (rewritten != null) {
                entity.setInstallationTokenEnc(rewritten);
                installationRepository.save(entity);
                installationsConverted++;
            }
        }

        int webhooksScanned = 0;
        int webhooksConverted = 0;
        for (WebhookRegistrationEntity entity : webhookRegistrationRepository.findAll()) {
            webhooksScanned++;
            String cipher = entity.getWebhookTokenEnc();
            if (cipher == null || cipher.isBlank()) {
                continue;
            }
            String rewritten = reencryptIfLegacy(
                    cipher, "webhook_registration", entity.getWorkspaceId() + "/" + entity.getRoutePath());
            if (rewritten != null) {
                entity.setWebhookTokenEnc(rewritten);
                webhookRegistrationRepository.save(entity);
                webhooksConverted++;
            }
        }

        int installationsLegacyRemaining = countLegacyRemaining(
                installationRepository.findAll(), InstallationEntity::getInstallationTokenEnc);
        int webhooksLegacyRemaining = countLegacyRemaining(
                webhookRegistrationRepository.findAll(), WebhookRegistrationEntity::getWebhookTokenEnc);

        return new ReencryptReport(
                installationsScanned,
                installationsConverted,
                webhooksScanned,
                webhooksConverted,
                installationsLegacyRemaining,
                webhooksLegacyRemaining);
    }

    private String reencryptIfLegacy(String cipher, String table, String rowId) {
        try {
            modern.decrypt(cipher);
            return null;
        } catch (RuntimeException modernFailure) {
            try {
                String plain = legacy.decrypt(cipher);
                return modern.encrypt(plain);
            } catch (RuntimeException legacyFailure) {
                // Neither format decrypted — surface the row identifier so prod logs point us at
                // the bad data. Re-throw so the surrounding transaction rolls back and we don't
                // half-drain the table.
                throw new IllegalStateException(
                        "TokenReencrypter: " + table + " row " + rowId
                                + " could not be decrypted with either the modern or legacy format",
                        legacyFailure);
            }
        }
    }

    private <T> int countLegacyRemaining(Iterable<T> rows, java.util.function.Function<T, String> getCipher) {
        int remaining = 0;
        for (T row : rows) {
            String cipher = getCipher.apply(row);
            if (cipher == null || cipher.isBlank()) {
                continue;
            }
            try {
                modern.decrypt(cipher);
            } catch (RuntimeException ignored) {
                remaining++;
            }
        }
        return remaining;
    }

    public record ReencryptReport(
            int installationsScanned,
            int installationsConverted,
            int webhooksScanned,
            int webhooksConverted,
            int installationsLegacyRemaining,
            int webhooksLegacyRemaining) {

        public boolean drained() {
            return installationsLegacyRemaining == 0 && webhooksLegacyRemaining == 0;
        }
    }
}
