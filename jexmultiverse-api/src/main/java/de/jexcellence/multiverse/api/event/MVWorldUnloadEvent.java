package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired <em>before</em> a managed world is unloaded from Bukkit. Cancellable.
 *
 * <p>Fired before players are evacuated to the default world's spawn, so a listener
 * that cancels leaves everyone where they are.
 *
 * <p>This does <em>not</em> fire during world deletion. By the time deletion reaches
 * the unload step the database row is already gone, so a cancellation there would
 * leave the world half-deleted. Veto deletions through {@link MVWorldDeleteEvent}
 * instead.
 *
 * <p>The snapshot is {@code null} when the world has no managed row, which happens
 * for a world that was unloaded outside JExMultiverse's knowledge. The identifier is
 * always present.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldUnloadEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull String identifier;
    private final @Nullable MVWorldSnapshot world;
    private final boolean save;

    private boolean cancelled;
    private @Nullable String cancellationReason;

    /**
     * Creates a new world unload event.
     *
     * @param identifier the world identifier
     * @param world      snapshot of the managed world, or {@code null} if unmanaged
     * @param save       whether chunks are saved before unloading
     */
    public MVWorldUnloadEvent(@NotNull final String identifier,
                              @Nullable final MVWorldSnapshot world,
                              final boolean save) {
        this.identifier = identifier;
        this.world = world;
        this.save = save;
    }

    /**
     * Returns the identifier of the world being unloaded.
     *
     * @return the world identifier
     */
    public @NotNull String getIdentifier() {
        return identifier;
    }

    /**
     * Returns a snapshot of the managed world, if one exists.
     *
     * @return the snapshot, or {@code null} when the world is not managed
     */
    public @Nullable MVWorldSnapshot getWorld() {
        return world;
    }

    /**
     * Returns whether chunks are saved before unloading.
     *
     * @return {@code true} if chunks are saved
     */
    public boolean isSave() {
        return save;
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
