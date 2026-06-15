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
 * Proves the event-driven persistence edge works end-to-end, and that the Spring Modulith
 * event publication registry retains an event when its listener cannot consume it.
 *
 * <p>Boots a focused slice — only the {@code persistence} package (the
 * {@link IndividualPersistenceListener}) plus Spring Boot auto-configuration (DataSource,
 * JdbcTemplate, the Spring Modulith JDBC event publication registry) — instead of the whole
 * {@code PlatformApplication}, whose broad {@code @ComponentScan} would drag in every
 * module's beans. It points at the local docker-compose Postgres (localhost:5433).
 *
 * <p>For these tests the external {@code egov-persister} container is stopped, so the ONLY
 * thing that can write the {@code individual} table is the in-process listener.
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

    /**
     * Happy path: publishing the event makes the row count go up by exactly one — and the
     * in-process listener (not the now-stopped egov-persister) is what wrote it.
     */
    @Test
    void publishingEventInsertsExactlyOneRow() {
        long before = countIndividuals();

        String id = "modulith-test-" + UUID.randomUUID();
        IndividualCreatedEvent event = new IndividualCreatedEvent(List.of(
                new IndividualCreatedEvent.Row(
                        id, "dev", "Test", "User", "9999999999",
                        "IND-TEST", "system", 1_700_000_000_000L, 1L, false)));

        try {
            transactionTemplate.executeWithoutResult(s -> eventPublisher.publishEvent(event));

            await().atMost(ofSeconds(10))
                    .untilAsserted(() -> assertThat(rowExists(id)).isTrue());

            long after = countIndividuals();
            System.out.printf("[before/after] individual rows: %d -> %d%n", before, after);
            assertThat(after).isEqualTo(before + 1);
        } finally {
            jdbcTemplate.update("DELETE FROM individual WHERE id = ?", id);
        }
    }

    /**
     * Durability: when the listener CANNOT consume the event (here the insert fails on a
     * NOT-NULL id), the event is NOT lost. The registry keeps it as an INCOMPLETE
     * publication (completion_date NULL), with the full payload, so it can be retried —
     * and with republish-outstanding-events-on-restart=true, redelivered on restart.
     */
    @Test
    void failedDeliveryKeepsEventInRegistryForRetry() {
        String marker = "DURABILITY-" + UUID.randomUUID();

        // id = null -> the listener's INSERT violates the NOT-NULL primary key -> it throws.
        IndividualCreatedEvent failing = new IndividualCreatedEvent(List.of(
                new IndividualCreatedEvent.Row(
                        null, "dev", "Test", "User", "9999999999",
                        marker, "system", 1_700_000_000_000L, 1L, false)));

        transactionTemplate.executeWithoutResult(s -> eventPublisher.publishEvent(failing));

        // The event is stored at publish time and stays incomplete because delivery fails.
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            Integer incomplete = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM event_publication " +
                            "WHERE completion_date IS NULL AND serialized_event LIKE ?",
                    Integer.class, "%" + marker + "%");
            assertThat(incomplete)
                    .as("event must be retained as an incomplete publication")
                    .isEqualTo(1);
        });

        // It was never completed (the receiver could not consume it)...
        Integer completed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_publication " +
                        "WHERE completion_date IS NOT NULL AND serialized_event LIKE ?",
                Integer.class, "%" + marker + "%");
        assertThat(completed).as("a failed delivery must not be marked complete").isZero();

        // ...yet the full event payload is durably stored in the DB.
        String json = jdbcTemplate.queryForObject(
                "SELECT serialized_event FROM event_publication WHERE serialized_event LIKE ? " +
                        "ORDER BY publication_date DESC LIMIT 1",
                String.class, "%" + marker + "%");
        assertThat(json).contains(marker);
        System.out.println("[durability] retained incomplete event payload: " + json);

        // cleanup so it is not endlessly republished on subsequent runs/restarts
        jdbcTemplate.update("DELETE FROM event_publication WHERE serialized_event LIKE ?",
                "%" + marker + "%");
    }

    private long countIndividuals() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM individual", Long.class);
        return count == null ? 0 : count;
    }

    private boolean rowExists(String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM individual WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }
}