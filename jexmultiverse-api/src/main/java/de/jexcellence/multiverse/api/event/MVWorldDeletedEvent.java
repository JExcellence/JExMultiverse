package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Fired <em>after</em> a managed world has been deleted.
 *
 * <p>By the time this fires the plot rows have cascaded, the database row is gone,
 * the world is unloaded, and its folder has been removed from disk. The snapshot is
 * the last known state and describes a world that no longer exists.
 *
 * <p>This event is informational and cannot be cancelled. Use
 * {@link MVWorldDeleteEvent} to intercept deletions before they happen.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldDeletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot world;
    private final @NotNull Instant deletedAt;

    /**
     * Creates a new event.
     *
     * @param world snapshot of the world involved
     */
    public MVWorldDeletedEvent(@NotNull final MVWorldSnapshot world) {
        this.world = world;
        this.deletedAt = Instant.now();
    }

    /**
     * Returns a snapshot of the world involved.
     *
     * @return snapshot of the world involved
     */
    public @NotNull MVWorldSnapshot getWorld() {
        return world;
    }

    /**
     * Returns when the world finished being deleted.
     *
     * @return the timestamp
     */
    public @NotNull Instant getDeletedAt() {
        return deletedAt;
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
