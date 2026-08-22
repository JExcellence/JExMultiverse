package de.jexcellence.multiverse.api.event;

import de.jexcellence.multiverse.api.MVWorldSnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * Fired once, after JExMultiverse has finished starting up, carrying every managed
 * world it knows about.
 *
 * <p>This exists because per-world lifecycle events are unusable during startup.
 * JExMultiverse loads and adopts its worlds while wiring services, which happens
 * before it registers its own listeners and before other plugins are necessarily
 * enabled - so a {@link MVWorldLoadedEvent} raised then would reach nobody. Those
 * events are suppressed during startup and this one is raised at the end instead.
 *
 * <p>Consumers that persist per-world state should use this to reconcile against the
 * live world set, which also catches worlds that disappeared while the server was
 * offline.
 *
 * <p>This event is informational and cannot be cancelled.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MVWorldsReadyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull List<MVWorldSnapshot> worlds;
    private final @NotNull Instant readyAt;

    /**
     * Creates a new worlds-ready event.
     *
     * @param worlds every managed world known at startup
     */
    public MVWorldsReadyEvent(@NotNull final List<MVWorldSnapshot> worlds) {
        this.worlds = List.copyOf(worlds);
        this.readyAt = Instant.now();
    }

    /**
     * Returns every managed world known at startup.
     *
     * @return an immutable list of world snapshots
     */
    public @NotNull List<MVWorldSnapshot> getWorlds() {
        return worlds;
    }

    /**
     * Returns when startup completed.
     *
     * @return the timestamp
     */
    public @NotNull Instant getReadyAt() {
        return readyAt;
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
