package com.devodox.stopatestimate.migration;

import com.devodox.stopatestimate.model.entity.InstallationEntity;
import com.devodox.stopatestimate.model.entity.WebhookRegistrationEntity;
import com.devodox.stopatestimate.repository.InstallationRepository;
import com.devodox.stopatestimate.repository.WebhookRegistrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenReencrypterTest {

    // 64-char hex = 256-bit key, matches SecurityConfig's validation rules for both key and salt.
    private static final String KEY_HEX =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SALT_HEX =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private TextEncryptor modern;
    private TextEncryptor legacy;
    private InstallationRepository installationRepository;
    private WebhookRegistrationRepository webhookRepository;
    private TokenReencrypter reencrypter;

    @BeforeEach
    void setUp() {
        modern = Encryptors.delux(KEY_HEX, SALT_HEX);
        legacy = Encryptors.text(KEY_HEX, SALT_HEX);
        installationRepository = mock(InstallationRepository.class);
        webhookRepository = mock(WebhookRegistrationRepository.class);
        reencrypter = new TokenReencrypter(installationRepository, webhookRepository, modern, legacy);
    }

    @Test
    void convertsLegacyRowsAndLeavesModernRowsAlone() {
        InstallationEntity legacyInstall = installation("ws-legacy", legacy.encrypt("legacy-token"));
        InstallationEntity modernInstall = installation("ws-modern", modern.encrypt("modern-token"));
        when(installationRepository.findAll()).thenReturn(List.of(legacyInstall, modernInstall));

        WebhookRegistrationEntity legacyHook = webhook("ws-legacy", "/webhooks/a", legacy.encrypt("legacy-hook"));
        WebhookRegistrationEntity modernHook = webhook("ws-modern", "/webhooks/b", modern.encrypt("modern-hook"));
        when(webhookRepository.findAll()).thenReturn(List.of(legacyHook, modernHook));

        TokenReencrypter.ReencryptReport report = reencrypter.run();

        verify(installationRepository, times(1)).save(legacyInstall);
        verify(installationRepository, never()).save(modernInstall);
        verify(webhookRepository, times(1)).save(legacyHook);
        verify(webhookRepository, never()).save(modernHook);

        // Legacy row was rewritten and the new ciphertext must round-trip through the modern encryptor.
        assertThat(modern.decrypt(legacyInstall.getInstallationTokenEnc())).isEqualTo("legacy-token");
        assertThat(modern.decrypt(legacyHook.getWebhookTokenEnc())).isEqualTo("legacy-hook");

        assertThat(report.installationsScanned()).isEqualTo(2);
        assertThat(report.installationsConverted()).isEqualTo(1);
        assertThat(report.webhooksScanned()).isEqualTo(2);
        assertThat(report.webhooksConverted()).isEqualTo(1);
        assertThat(report.installationsLegacyRemaining()).isZero();
        assertThat(report.webhooksLegacyRemaining()).isZero();
        assertThat(report.drained()).isTrue();
    }

    @Test
    void secondRunIsIdempotent() {
        InstallationEntity install = installation("ws-1", legacy.encrypt("first-token"));
        WebhookRegistrationEntity hook = webhook("ws-1", "/webhooks/x", legacy.encrypt("first-hook"));
        List<InstallationEntity> installations = new ArrayList<>(List.of(install));
        List<WebhookRegistrationEntity> webhooks = new ArrayList<>(List.of(hook));
        when(installationRepository.findAll()).thenReturn(installations);
        when(webhookRepository.findAll()).thenReturn(webhooks);

        TokenReencrypter.ReencryptReport first = reencrypter.run();
        assertThat(first.installationsConverted()).isEqualTo(1);
        assertThat(first.webhooksConverted()).isEqualTo(1);

        // Second call against the same in-memory entities: ciphertext is now modern, so conversion
        // counts should be zero and no additional save calls should hit the repositories.
        TokenReencrypter.ReencryptReport second = reencrypter.run();

        assertThat(second.installationsScanned()).isEqualTo(1);
        assertThat(second.installationsConverted()).isZero();
        assertThat(second.webhooksScanned()).isEqualTo(1);
        assertThat(second.webhooksConverted()).isZero();
        assertThat(second.drained()).isTrue();
        verify(installationRepository, times(1)).save(any(InstallationEntity.class));
        verify(webhookRepository, times(1)).save(any(WebhookRegistrationEntity.class));
    }

    @Test
    void undecryptableRowSurfacesIdentifierInException() {
        InstallationEntity bad = installation("ws-bad", "this-is-not-valid-ciphertext");
        when(installationRepository.findAll()).thenReturn(List.of(bad));
        when(webhookRepository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> reencrypter.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("installation")
                .hasMessageContaining("ws-bad");
    }

    @Test
    void skipsNullAndBlankCiphertextWithoutException() {
        InstallationEntity nullCipher = installation("ws-null", null);
        InstallationEntity blankCipher = installation("ws-blank", "");
        when(installationRepository.findAll()).thenReturn(List.of(nullCipher, blankCipher));
        when(webhookRepository.findAll()).thenReturn(List.of());

        TokenReencrypter.ReencryptReport report = reencrypter.run();

        verify(installationRepository, never()).save(any(InstallationEntity.class));
        assertThat(report.installationsScanned()).isEqualTo(2);
        assertThat(report.installationsConverted()).isZero();
        assertThat(report.installationsLegacyRemaining()).isZero();
    }

    private static InstallationEntity installation(String workspaceId, String cipher) {
        InstallationEntity e = new InstallationEntity();
        e.setWorkspaceId(workspaceId);
        e.setInstallationTokenEnc(cipher);
        return e;
    }

    private static WebhookRegistrationEntity webhook(String workspaceId, String routePath, String cipher) {
        WebhookRegistrationEntity e = new WebhookRegistrationEntity();
        e.setWorkspaceId(workspaceId);
        e.setRoutePath(routePath);
        e.setWebhookTokenEnc(cipher);
        return e;
    }
}
