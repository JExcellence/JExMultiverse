package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired <em>before</em> a managed world is regenerated in place. Cancellable.
 *
 * <p>A reset destroys terrain but keeps the world's identity: the database row, its
 * settings, its spawn and its identifier all survive. That is what distinguishes it
 * from delete-then-create, and consumers should treat the two differently. On a
 * delete, drop your per-world data. On a reset, keep your configuration but discard
 * anything tied to coordinates, because every block is about to change.
 *
 * <p>Fired on the main server thread before players are evacuated and before any
 * file is touched. Cancelling aborts the reset entirely.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldResetEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot world;
    private final boolean newSeed;

    private boolean cancelled;
    private @Nullable String cancellationReason;

    /**
     * Creates a new world reset event.
     *
     * @param world   snapshot of the world about to be regenerated
     * @param newSeed whether the world will be regenerated with a fresh seed
     */
    public MVWorldResetEvent(@NotNull final MVWorldSnapshot world, final boolean newSeed) {
        this.world = world;
        this.newSeed = newSeed;
    }

    /**
     * Returns a snapshot of the world about to be regenerated.
     *
     * @return snapshot of the world about to be regenerated
     */
    public @NotNull MVWorldSnapshot getWorld() {
        return world;
    }

    /**
     * Returns whether the world will be regenerated with a fresh seed.
     *
     * @return {@code true} if the seed changes
     */
    public boolean isNewSeed() {
        return newSeed;
    }

    /**
     * Returns the optional reason why this event was cancelled.
     *
     * @return optional reason why this event was cancelled
     */
    public @Nullable String getCancellationReason() {
        return cancellationReason;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(final boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * Cancels this event with a specific reason.
     *
     * @param reason the cancellation reason
     */
    public void setCancelled(@NotNull final String reason) {
        this.cancelled = true;
        this.cancellationReason = reason;
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
