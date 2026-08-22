package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Fired <em>after</em> a managed world has been created and persisted.
 *
 * <p>Fires once per creation regardless of which path produced the world - the
 * Paper {@code Bukkit.createWorld} path, the Folia NMS runtime loader, or adoption
 * of a world that was already loaded in Bukkit. All three converge on the same
 * persistence step, which is where this event is raised.
 *
 * <p>This event is informational and cannot be cancelled. Use
 * {@link MVWorldCreateEvent} to intercept creations before they happen.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldCreatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot world;
    private final @NotNull Instant createdAt;

    /**
     * Creates a new event.
     *
     * @param world snapshot of the world involved
     */
    public MVWorldCreatedEvent(@NotNull final MVWorldSnapshot world) {
        this.world = world;
        this.createdAt = Instant.now();
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
     * Returns when the world finished being created.
     *
     * @return the timestamp
     */
    public @NotNull Instant getCreatedAt() {
        return createdAt;
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
