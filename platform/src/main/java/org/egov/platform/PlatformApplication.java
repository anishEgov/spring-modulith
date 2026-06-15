package org.egov.platform;

import org.egov.tracer.config.TracerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Single entry point for the modular monolith.
 *
 * Modules live as first-level packages under {@code org.egov.platform}:
 * <ul>
 *     <li>{@code idgen}        - provider: ID generation</li>
 *     <li>{@code localization} - provider: localized messages</li>
 *     <li>{@code individual}   - consumer: calls idgen + localization in-process</li>
 * </ul>
 *
 * Boundaries are enforced by {@code org.egov.platform.ModularityTests}, not by the network.
 */
@SpringBootApplication
@EnableCaching
// @ApplicationModuleListener is meta-annotated @Async; @EnableAsync makes the async
// (off-thread, after-commit) event handling in the persistence module actually take effect.
@EnableAsync
@Import(TracerConfiguration.class)
// Scan the platform modules plus the egov library packages whose beans the modules
// autowire (mdms-client). individual's MainConfiguration additionally scans
// org.egov.common and org.egov.encryption.
@ComponentScan(basePackages = {"org.egov.platform", "org.egov.mdms"})
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
