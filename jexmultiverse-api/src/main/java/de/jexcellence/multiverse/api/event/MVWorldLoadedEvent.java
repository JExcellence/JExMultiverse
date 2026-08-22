package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Fired <em>after</em> a managed world has been loaded into Bukkit and cached.
 *
 * <p>Also fires when the world was already present in Bukkit and was simply
 * re-cached, so handlers should be idempotent.
 *
 * <p>This event is informational and cannot be cancelled. Use
 * {@link MVWorldLoadEvent} to intercept loads before they happen.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldLoadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot world;
    private final @NotNull Instant loadedAt;

    /**
     * Creates a new event.
     *
     * @param world snapshot of the world involved
     */
    public MVWorldLoadedEvent(@NotNull final MVWorldSnapshot world) {
        this.world = world;
        this.loadedAt = Instant.now();
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
     * Returns when the world finished loading.
     *
     * @return the timestamp
     */
    public @NotNull Instant getLoadedAt() {
        return loadedAt;
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
