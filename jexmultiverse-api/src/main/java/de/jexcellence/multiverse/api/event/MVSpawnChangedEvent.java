package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Fired <em>after</em> a managed world's spawn point has been moved.
 *
 * <p>This event is informational and cannot be cancelled.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVSpawnChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot world;
    private final @NotNull Location spawn;
    private final @NotNull Instant changedAt;

    /**
     * Creates a new spawn changed event.
     *
     * @param world snapshot of the world, carrying the new spawn coordinates
     * @param spawn the new spawn location
     */
    public MVSpawnChangedEvent(@NotNull final MVWorldSnapshot world, @NotNull final Location spawn) {
        this.world = world;
        this.spawn = spawn;
        this.changedAt = Instant.now();
    }

    /**
     * Returns a snapshot of the world whose spawn moved.
     *
     * @return snapshot of the world
     */
    public @NotNull MVWorldSnapshot getWorld() {
        return world;
    }

    /**
     * Returns the new spawn location.
     *
     * @return the new spawn location
     */
    public @NotNull Location getSpawn() {
        return spawn;
    }

    /**
     * Returns when the spawn was moved.
     *
     * @return the timestamp
     */
    public @NotNull Instant getChangedAt() {
        return changedAt;
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
