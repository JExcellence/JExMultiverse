/**
 * Bukkit events fired by JExMultiverse for world lifecycle changes.
 *
 * <p>Events carry immutable {@link de.jexcellence.multiverse.api.MVWorldSnapshot}
 * records rather than entities, so consumers never gain a transitive dependency on
 * JEHibernate or Jakarta Persistence.
 *
 * <p>Every event in this package is <em>synchronous</em>. JExMultiverse marshals to
 * the main thread at the call site, because most of its world operations resolve on
 * an asynchronous database pool. Handlers therefore run on the main server thread and
 * may touch the Bukkit API directly, but must not block.
 *
 * <p>Present-tense names ({@code MVWorldDeleteEvent}) are cancellable and fire before
 * the operation. Past-tense names ({@code MVWorldDeletedEvent}) are informational and
 * fire after it completed.
 *
 * <p>No lifecycle event fires while JExMultiverse is still starting up, because
 * listeners are registered after the world cache is populated. Consumers needing the
 * initial world list should listen for
 * {@link de.jexcellence.multiverse.api.event.MVWorldsReadyEvent} instead.
 */
package de.jexcellence.multiverse.api.event;
