package de.jexcellence.multiverse.event;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
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
     * Fires a cancellable event on the main thread and reports whether it was cancelled.
     *
     * <p>The decision has to be available before the operation continues, which is
     * why the result is a future rather than a boolean: when the caller is off the
     * main thread the event is marshalled and the caller resumes afterwards, instead
     * of racing ahead of the handlers.
     *
     * <p>This is what public, future-returning API methods must use.
     * {@link #fireSync(Event)} throws off-thread, which is correct for internal
     * main-thread-only operations and wrong for an API another plugin can call from
     * wherever it likes.
     *
     * @param event     the cancellable event to fire
     * @param scheduler the platform scheduler used to reach the main thread
     * @return a future completing with {@code true} if a listener cancelled the event
     */
    public static @NotNull CompletableFuture<Boolean> fireCancellable(
            @NotNull final Event event, @NotNull final PlatformScheduler scheduler) {
        if (BOOTSTRAPPING.get()) {
            return CompletableFuture.completedFuture(false);
        }
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(callAndReadCancelled(event));
        }
        final var decided = new CompletableFuture<Boolean>();
        scheduler.runSync(() -> {
            try {
                decided.complete(callAndReadCancelled(event));
            } catch (final RuntimeException e) {
                // Never leave the caller's future hanging: a listener that throws
                // would otherwise stall the whole world operation forever.
                decided.completeExceptionally(e);
            }
        });
        return decided;
    }

    private static boolean callAndReadCancelled(@NotNull final Event event) {
        Bukkit.getPluginManager().callEvent(event);
        return event instanceof Cancellable cancellable && cancellable.isCancelled();
    }

    /**
     * Fires a cancellable event inline, requiring the caller to be on the main thread.
     *
     * <p>For operations that are main-thread-only in Bukkit regardless of this event -
     * loading and unloading a world - where being off-thread is already a bug and a
     * loud failure beats a silent one.
     *
     * <p>Do not use this from anything reachable through the public API.
     * {@code MultiverseService} returns futures and is called by other plugins from
     * their own async work, so its cancellable events go through
     * {@link #fireCancellable(Event, PlatformScheduler)}.
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
        return callAndReadCancelled(event);
    }
}
