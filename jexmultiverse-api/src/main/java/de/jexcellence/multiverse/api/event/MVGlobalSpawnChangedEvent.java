package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Fired <em>after</em> the global-spawn world has changed.
 *
 * <p>Only one world may hold the global-spawn flag, so designating a new one clears
 * it from whichever world held it before. The previous holder is carried here because
 * a consumer tracking spawn state cannot otherwise tell what was demoted.
 *
 * <p>This event is informational and cannot be cancelled.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVGlobalSpawnChangedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull MVWorldSnapshot world;
    private final @Nullable String previousIdentifier;
    private final @NotNull Instant changedAt;

    /**
     * Creates a new global spawn changed event.
     *
     * @param world              snapshot of the world that is now the global spawn
     * @param previousIdentifier identifier of the previous global-spawn world, or
     *                           {@code null} if there was none
     */
    public MVGlobalSpawnChangedEvent(@NotNull final MVWorldSnapshot world,
                                     @Nullable final String previousIdentifier) {
        this.world = world;
        this.previousIdentifier = previousIdentifier;
        this.changedAt = Instant.now();
    }

    /**
     * Returns a snapshot of the world that is now the global spawn.
     *
     * @return snapshot of the new global-spawn world
     */
    public @NotNull MVWorldSnapshot getWorld() {
        return world;
    }

    /**
     * Returns the identifier of the world that previously held the global spawn.
     *
     * @return the previous identifier, or {@code null} if there was none
     */
    public @Nullable String getPreviousIdentifier() {
        return previousIdentifier;
    }

    /**
     * Returns when the global spawn changed.
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
