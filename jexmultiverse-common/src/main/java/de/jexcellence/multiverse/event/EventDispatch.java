package de.jexcellence.multiverse.event;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central dispatcher for JExMultiverse's public events.
 *
 * <p>Exists to solve two problems that every fire site would otherwise repeat.
 *
 * <p><b>Thread affinity.</b> Most world operations resolve on JEHibernate's async
 * pool, and {@code PluginManager#callEvent} throws when an event declared
 * synchronous is fired off the main thread. {@link #fire(Event, PlatformScheduler)}
 * dispatches inline when already on the main thread and hops through the scheduler
 * otherwise, so callers do not have to know which thread they are on.
 *
 * <p><b>Startup suppression.</b> JExMultiverse loads and adopts its worlds while
 * wiring services, which happens before it registers its own listeners and before
 * other plugins are necessarily enabled. Per-world events raised then would reach
 * nobody and only pollute the log, so dispatch is disabled until
 * {@link #bootstrapComplete()} is called at the end of enable. Consumers observe the
 * initial world set through {@code MVWorldsReadyEvent} instead.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public final class EventDispatch {

    /**
     * Starts {@code true} and is also reset by {@link #bootstrapStart()} so a plugin
     * reload does not inherit the previous session's completed state.
     */
    private static final AtomicBoolean BOOTSTRAPPING = new AtomicBoolean(true);

    private EventDispatch() {
        // Utility class
    }

    /**
     * Marks the start of a bootstrap cycle, suppressing dispatch until
     * {@link #bootstrapComplete()}. Call at the top of plugin enable so a reload
     * behaves like a fresh start.
     */
    public static void bootstrapStart() {
        BOOTSTRAPPING.set(true);
    }

    /**
     * Marks bootstrap as finished and enables dispatch. Call once listeners are
     * registered and the world cache is populated.
     */
    public static void bootstrapComplete() {
        BOOTSTRAPPING.set(false);
    }

    /**
     * Returns whether event dispatch is currently suppressed.
     *
     * @return {@code true} while bootstrapping
     */
    public static boolean isBootstrapping() {
        return BOOTSTRAPPING.get();
    }

    /**
     * Fires an event on the main server thread, or drops it if still bootstrapping.
     *
     * <p>Dispatch is asynchronous when the caller is off the main thread, so this
     * must not be used for cancellable events - the caller would race ahead of the
     * handlers. Use {@link #fireSync(Event)} for those.
     *
     * @param event     the event to fire
     * @param scheduler the platform scheduler used to reach the main thread
     */
    public static void fire(@NotNull final Event event, @NotNull final PlatformScheduler scheduler) {
        if (BOOTSTRAPPING.get()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            scheduler.runSync(() -> Bukkit.getPluginManager().callEvent(event));
        }
    }

    /**
     * Fires a cancellable event inline and reports whether it was cancelled.
     *
     * <p>The caller must already be on the main server thread. Cancellable events
     * cannot be marshalled, because the decision has to be available before the
     * operation continues. Every cancellable fire site in JExMultiverse is reached
     * from a command handler or GUI view, both of which are main-thread.
     *
     * @param event the cancellable event to fire
     * @return {@code true} if a listener cancelled the event
     * @throws IllegalStateException if called off the main server thread
     */
    public static boolean fireSync(@NotNull final Event event) {
        if (BOOTSTRAPPING.get()) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "Cancellable event " + event.getEventName() + " must be fired on the main thread");
        }
        Bukkit.getPluginManager().callEvent(event);
        return event instanceof Cancellable cancellable && cancellable.isCancelled();
    }
}
