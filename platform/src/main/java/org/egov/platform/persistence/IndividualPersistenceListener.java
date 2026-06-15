package org.egov.platform.persistence;

import lombok.extern.slf4j.Slf4j;
import org.egov.platform.individual.IndividualCreatedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Persists individuals in response to {@link IndividualCreatedEvent}.
 *
 * <p>{@code @ApplicationModuleListener} = {@code @Async} +
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} + {@code @Transactional}. So
 * this runs:
 * <ul>
 *   <li><b>on a different thread</b> from the publisher (async), and</li>
 *   <li><b>only after</b> the publishing transaction commits (no write on rollback).</li>
 * </ul>
 * The event is stored in the {@code event_publication} registry before the listener runs
 * and marked complete after it returns — giving at-least-once delivery with replay on
 * restart. This is the in-process equivalent of the old Kafka → {@code egov-persister} hop.
 */
@Component
@Slf4j
public class IndividualPersistenceListener {

    private static final String INSERT_SQL = """
            INSERT INTO individual
                (id, tenantId, givenName, familyName, mobileNumber, individualId,
                 createdBy, lastModifiedBy, createdTime, lastModifiedTime, rowVersion, isDeleted)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public IndividualPersistenceListener(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @ApplicationModuleListener
    public void on(IndividualCreatedEvent event) {
        log.info("[persistence] received IndividualCreatedEvent with {} individual(s) on thread {}",
                event.individuals().size(), Thread.currentThread().getName());

        for (IndividualCreatedEvent.Row r : event.individuals()) {
            jdbcTemplate.update(INSERT_SQL,
                    r.id(), r.tenantId(), r.givenName(), r.familyName(), r.mobileNumber(),
                    r.individualId(), r.createdBy(), r.createdBy(),
                    r.createdTime(), r.createdTime(), r.rowVersion(), r.isDeleted());
            log.info("[persistence] persisted individual id={}", r.id());
        }
    }
}