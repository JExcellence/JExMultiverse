package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldType;
import org.bukkit.World;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fired <em>before</em> a managed world is created. Cancellable.
 *
 * <p>Fired on the main server thread after the edition, world-type and
 * already-exists guards have passed, but before any directory or database row is
 * touched. Cancelling aborts the creation entirely.
 *
 * <p>There is no world yet, so this event carries the requested parameters rather
 * than a snapshot. Use {@link MVWorldCreatedEvent} to react to the finished world.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldCreateEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull String identifier;
    private final World.@NotNull Environment environment;
    private final @NotNull MVWorldType type;

    private boolean cancelled;
    private @Nullable String cancellationReason;

    /**
     * Creates a new world create event.
     *
     * @param identifier  the requested world identifier
     * @param environment the requested world environment
     * @param type        the requested generation type
     */
    public MVWorldCreateEvent(@NotNull final String identifier,
                              final World.@NotNull Environment environment,
                              @NotNull final MVWorldType type) {
        this.identifier = identifier;
        this.environment = environment;
        this.type = type;
    }

    /**
     * Returns the requested world identifier.
     *
     * @return the requested world identifier
     */
    public @NotNull String getIdentifier() {
        return identifier;
    }

    /**
     * Returns the requested world environment.
     *
     * @return the requested world environment
     */
    public World.@NotNull Environment getEnvironment() {
        return environment;
    }

    /**
     * Returns the requested generation type.
     *
     * @return the requested generation type
     */
    public @NotNull MVWorldType getType() {
        return type;
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
