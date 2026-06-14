package org.egov.platform;

import org.egov.tracer.config.TracerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

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
@Import(TracerConfiguration.class)
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
