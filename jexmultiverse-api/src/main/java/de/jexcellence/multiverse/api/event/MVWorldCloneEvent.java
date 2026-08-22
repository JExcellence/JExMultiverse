package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired <em>before</em> a managed world is copied to a new identifier. Cancellable.
 *
 * <p>Fired on the main server thread before the source world is saved and before any
 * file is copied. Cancelling aborts the clone.
 *
 * <p>Plot claims are never copied - a cloned {@code PLOT} world starts unclaimed.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldCloneEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot source;
    private final @NotNull String targetIdentifier;

    private boolean cancelled;
    private @Nullable String cancellationReason;

    /**
     * Creates a new world clone event.
     *
     * @param source           snapshot of the world being copied
     * @param targetIdentifier the identifier the copy will receive
     */
    public MVWorldCloneEvent(@NotNull final MVWorldSnapshot source,
                             @NotNull final String targetIdentifier) {
        this.source = source;
        this.targetIdentifier = targetIdentifier;
    }

    /**
     * Returns a snapshot of the world being copied.
     *
     * @return snapshot of the source world
     */
    public @NotNull MVWorldSnapshot getSource() {
        return source;
    }

    /**
     * Returns the identifier the copy will receive.
     *
     * @return the target identifier
     */
    public @NotNull String getTargetIdentifier() {
        return targetIdentifier;
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
