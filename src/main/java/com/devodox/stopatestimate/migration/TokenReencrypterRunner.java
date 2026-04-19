package com.devodox.stopatestimate.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the SEC-03 drain pass once per startup. Emits two INFO lines: the conversion summary and
 * the verification summary. On a fully-migrated DB both {@code legacy_remaining} values will be
 * {@code 0}. The fallback branch has been removed from {@code SecurityConfig.textEncryptor}
 * (commit {@code 1caa2b6}); this runner is retained as an idempotent guardrail.
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
            // On a fully-migrated DB the drain finds zero legacy rows, so a failure here is safe
            // to swallow: a transient DB blip at startup should not crash the app, and the next
            // boot will retry. Any genuinely un-migrated row will be caught on the next restart.
            log.warn("TokenReencrypter drain pass failed — will retry on next startup", e);
        }
    }
}
