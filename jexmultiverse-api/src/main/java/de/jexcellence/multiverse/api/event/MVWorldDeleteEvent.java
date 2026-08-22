package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired <em>before</em> a managed world is deleted. Cancellable.
 *
 * <p>Fired on the main server thread before plot rows cascade, before the world is
 * unloaded, and before its folder is removed. Cancelling aborts the entire deletion.
 *
 * <p>Listeners holding per-world data should use this to migrate or archive it while
 * the world still exists.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldDeleteEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot world;

    private boolean cancelled;
    private @Nullable String cancellationReason;

    /**
     * Creates a new event.
     *
     * @param world snapshot of the world involved
     */
    public MVWorldDeleteEvent(@NotNull final MVWorldSnapshot world) {
        this.world = world;
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
