/**
 * The {@code persistence} module: an in-process replacement for the external
 * {@code egov-persister} service.
 *
 * <p>It listens for {@link org.egov.platform.individual.IndividualCreatedEvent} via a
 * Spring Modulith {@code @ApplicationModuleListener} and writes the individuals to the
 * database asynchronously, after the publishing transaction commits. This is the
 * "modulith is nothing without events" edge: cross-module communication by event instead
 * of by direct call or message broker.
 */
package org.egov.platform.persistence;