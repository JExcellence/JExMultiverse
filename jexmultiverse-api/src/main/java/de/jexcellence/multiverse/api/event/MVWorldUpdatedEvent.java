package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Fired <em>after</em> a managed world's persisted settings have changed.
 *
 * <p>Covers everything the editor can change: PvP, enter permission, build lock and
 * its interaction mode, gamerules, fixed time, weather lock and difficulty. The
 * snapshot carries the state after the change.
 *
 * <p>Spawn moves and global-spawn changes have their own events, because consumers
 * usually care about those specifically.
 *
 * <p>This event is informational and cannot be cancelled.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldUpdatedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot world;
    private final @NotNull Instant updatedAt;

    /**
     * Creates a new world updated event.
     *
     * @param world snapshot of the world after the change
     */
    public MVWorldUpdatedEvent(@NotNull final MVWorldSnapshot world) {
        this.world = world;
        this.updatedAt = Instant.now();
    }

    /**
     * Returns a snapshot of the world after the change.
     *
     * @return snapshot of the updated world
     */
    public @NotNull MVWorldSnapshot getWorld() {
        return world;
    }

    /**
     * Returns when the settings were saved.
     *
     * @return the timestamp
     */
    public @NotNull Instant getUpdatedAt() {
        return updatedAt;
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
