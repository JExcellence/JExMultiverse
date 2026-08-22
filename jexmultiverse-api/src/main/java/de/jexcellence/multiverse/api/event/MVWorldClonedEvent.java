package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Fired <em>after</em> a managed world has been copied to a new identifier.
 *
 * <p>The clone is loaded, cached and persisted with its own database row. It carries
 * the source world's type, environment, spawn and generation overrides, but never its
 * global-spawn flag (only one world may hold that) and never its plot claims.
 *
 * <p>This event is informational and cannot be cancelled. Use
 * {@link MVWorldCloneEvent} to intercept clones before they happen.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldClonedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot source;
    private final @NotNull MVWorldSnapshot clone;
    private final @NotNull Instant clonedAt;

    /**
     * Creates a new world cloned event.
     *
     * @param source snapshot of the world that was copied
     * @param clone  snapshot of the newly created copy
     */
    public MVWorldClonedEvent(@NotNull final MVWorldSnapshot source,
                              @NotNull final MVWorldSnapshot clone) {
        this.source = source;
        this.clone = clone;
        this.clonedAt = Instant.now();
    }

    /**
     * Returns a snapshot of the world that was copied.
     *
     * @return snapshot of the source world
     */
    public @NotNull MVWorldSnapshot getSource() {
        return source;
    }

    /**
     * Returns a snapshot of the newly created copy.
     *
     * @return snapshot of the clone
     */
    public @NotNull MVWorldSnapshot getClone() {
        return clone;
    }

    /**
     * Returns when the clone finished.
     *
     * @return the timestamp
     */
    public @NotNull Instant getClonedAt() {
        return clonedAt;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Returns the handler list for this event type.
     *
     * @return the handler list
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
