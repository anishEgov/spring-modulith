package org.egov.platform.persistence;

import java.util.List;
import java.util.UUID;

import org.egov.platform.individual.IndividualCreatedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Proves the event-driven persistence edge actually works end-to-end.
 *
 * <p>Boots a focused slice — only the {@code persistence} package (the
 * {@link IndividualPersistenceListener}) plus Spring Boot auto-configuration (DataSource,
 * JdbcTemplate, the Spring Modulith JDBC event publication registry) — instead of the whole
 * {@code PlatformApplication}, whose broad {@code @ComponentScan} would drag in every
 * module's beans. It points at the local docker-compose Postgres (localhost:5433) via
 * {@code application.properties}.
 *
 * <p>We publish a real {@link IndividualCreatedEvent} inside a transaction (so the
 * {@code AFTER_COMMIT} listener fires), then poll until the row lands in the
 * {@code individual} table — i.e. the async {@code @ApplicationModuleListener} ran.
 */
@SpringBootTest(classes = PersistenceModuleTests.TestApp.class)
class PersistenceModuleTests {

    /** Minimal app: component scan is rooted at this (the persistence) package only. */
    @SpringBootApplication
    @EnableAsync
    static class TestApp {
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void persistsIndividualWhenEventIsPublished() {
        String id = "modulith-test-" + UUID.randomUUID();

        IndividualCreatedEvent event = new IndividualCreatedEvent(List.of(
                new IndividualCreatedEvent.Row(
                        id, "dev", "Test", "User", "9999999999",
                        "IND-TEST", "system", 1_700_000_000_000L, 1L, false)));

        // Publish inside a transaction so the AFTER_COMMIT listener is triggered on commit.
        transactionTemplate.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        // The listener runs asynchronously on another thread — wait for the row to appear.
        await().atMost(ofSeconds(10))
                .untilAsserted(() -> assertThat(rowExists(id))
                        .as("persistence listener should have inserted individual %s", id)
                        .isTrue());

        // The modulith registry should have recorded the publication and marked it completed.
        Integer completed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NOT NULL",
                Integer.class);
        assertThat(completed)
                .as("event_publication registry should hold at least one completed event")
                .isNotNull()
                .isGreaterThanOrEqualTo(1);
    }

    private boolean rowExists(String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM individual WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }
}