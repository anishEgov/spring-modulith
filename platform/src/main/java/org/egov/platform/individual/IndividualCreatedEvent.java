package org.egov.platform.individual;

import java.util.List;

/**
 * Published by the {@code individual} module once individuals have been created, so the
 * {@code persistence} module can write them to the database asynchronously.
 *
 * <p>This is the event-driven replacement for the former Kafka hop
 * ({@code save-individual-topic} → external {@code egov-persister} container). It is an
 * <b>exposed</b> type (lives in the module's root package) so other modules may legally
 * depend on it.
 *
 * <p>It carries a flat projection ({@link Row}) rather than the full {@code Individual}
 * domain model, so the payload stays small, stable, and trivially JSON-serializable for
 * the Spring Modulith event publication registry.
 */
public record IndividualCreatedEvent(List<Row> individuals) {

    /** The columns the persistence listener writes to the {@code individual} table. */
    public record Row(
            String id,
            String tenantId,
            String givenName,
            String familyName,
            String mobileNumber,
            String individualId,
            String createdBy,
            Long createdTime,
            Long rowVersion,
            Boolean isDeleted
    ) {}
}
