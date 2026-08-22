package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Fired <em>after</em> a managed world has been unloaded from Bukkit.
 *
 * <p>Players that were in the world have already been evacuated and the world cache
 * entry has been invalidated. The world directory still exists on disk - this event
 * says nothing about deletion.
 *
 * <p>The snapshot is {@code null} when the world had no managed row. The identifier
 * is always present.
 *
 * <p>This event is informational and cannot be cancelled. Use
 * {@link MVWorldUnloadEvent} to intercept unloads before they happen.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldUnloadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull String identifier;
    private final @Nullable MVWorldSnapshot world;
    private final boolean saved;
    private final @NotNull Instant unloadedAt;

    /**
     * Creates a new world unloaded event.
     *
     * @param identifier the world identifier
     * @param world      snapshot of the managed world, or {@code null} if unmanaged
     * @param saved      whether chunks were saved before unloading
     */
    public MVWorldUnloadedEvent(@NotNull final String identifier,
                                @Nullable final MVWorldSnapshot world,
                                final boolean saved) {
        this.identifier = identifier;
        this.world = world;
        this.saved = saved;
        this.unloadedAt = Instant.now();
    }

    /**
     * Returns the identifier of the world that was unloaded.
     *
     * @return the world identifier
     */
    public @NotNull String getIdentifier() {
        return identifier;
    }

    /**
     * Returns a snapshot of the managed world, if one existed.
     *
     * @return the snapshot, or {@code null} when the world was not managed
     */
    public @Nullable MVWorldSnapshot getWorld() {
        return world;
    }

    /**
     * Returns whether chunks were saved before unloading.
     *
     * @return {@code true} if chunks were saved
     */
    public boolean isSaved() {
        return saved;
    }

    /**
     * Returns when the world finished unloading.
     *
     * @return the timestamp
     */
    public @NotNull Instant getUnloadedAt() {
        return unloadedAt;
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
