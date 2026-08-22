package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * Fired <em>after</em> a managed world has been regenerated in place.
 *
 * <p>The world exists again with the same identifier and settings, but every block
 * is new. Consumers storing coordinates in this world should treat them all as
 * invalid; consumers storing per-world configuration can keep it.
 *
 * <p>For a {@code PLOT} world the plot rows were also purged, so the world starts
 * completely unclaimed.
 *
 * <p>This event is informational and cannot be cancelled. Use
 * {@link MVWorldResetEvent} to intercept resets before they happen.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldResetCompletedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot world;
    private final boolean newSeed;
    private final int plotsPurged;
    private final @NotNull Instant resetAt;

    /**
     * Creates a new world reset completed event.
     *
     * @param world       snapshot of the regenerated world
     * @param newSeed     whether the world was regenerated with a fresh seed
     * @param plotsPurged how many plot claims were removed, zero for non-plot worlds
     */
    public MVWorldResetCompletedEvent(@NotNull final MVWorldSnapshot world,
                                      final boolean newSeed,
                                      final int plotsPurged) {
        this.world = world;
        this.newSeed = newSeed;
        this.plotsPurged = plotsPurged;
        this.resetAt = Instant.now();
    }

    /**
     * Returns a snapshot of the regenerated world.
     *
     * @return snapshot of the regenerated world
     */
    public @NotNull MVWorldSnapshot getWorld() {
        return world;
    }

    /**
     * Returns whether the world was regenerated with a fresh seed.
     *
     * @return {@code true} if the seed changed
     */
    public boolean isNewSeed() {
        return newSeed;
    }

    /**
     * Returns how many plot claims were removed by the reset.
     *
     * @return the number of purged plots, zero for non-plot worlds
     */
    public int getPlotsPurged() {
        return plotsPurged;
    }

    /**
     * Returns when the reset finished.
     *
     * @return the timestamp
     */
    public @NotNull Instant getResetAt() {
        return resetAt;
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
