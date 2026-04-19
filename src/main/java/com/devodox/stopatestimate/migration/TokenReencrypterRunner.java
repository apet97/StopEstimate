package com.devodox.stopatestimate.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the SEC-03 drain pass once per startup. Emits two INFO lines that deploy checks grep for:
 * the conversion summary and the verification summary ({@code legacy_remaining=0} is the gate
 * before the fallback branch can be removed from {@code SecurityConfig.textEncryptor}).
 */
@Component
public class TokenReencrypterRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TokenReencrypterRunner.class);

    private final TokenReencrypter tokenReencrypter;

    public TokenReencrypterRunner(TokenReencrypter tokenReencrypter) {
        this.tokenReencrypter = tokenReencrypter;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            TokenReencrypter.ReencryptReport report = tokenReencrypter.run();
            log.info(
                    "TokenReencrypter: installationsScanned={} installationsConverted={} webhooksScanned={} webhooksConverted={}",
                    report.installationsScanned(),
                    report.installationsConverted(),
                    report.webhooksScanned(),
                    report.webhooksConverted());
            log.info(
                    "TokenReencrypter verification: installations_legacy_remaining={} webhooks_legacy_remaining={}",
                    report.installationsLegacyRemaining(),
                    report.webhooksLegacyRemaining());
        } catch (RuntimeException e) {
            // Live traffic keeps working via the legacy fallback in SecurityConfig.textEncryptor,
            // so swallowing here is safe: a transient DB blip at startup shouldn't crash the app,
            // and the next restart retries.
            log.warn("TokenReencrypter drain pass failed — will retry on next startup", e);
        }
    }
}
