package de.jexcellence.multiverse.folia;

import de.jexcellence.multiverse.spi.RuntimeWorldLoader;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Folia runtime world loader. Bypasses Folia's patched
 * {@code CraftServer#createWorld} by reconstructing the same NMS-level
 * sequence that the original method performed before the Folia patch
 * replaced its body with {@code throw new UnsupportedOperationException}.
 *
 * <p>The actual reflection-heavy {@code ServerLevel} construction lives in
 * {@link FoliaNmsWorldFactory}; this class is the thin SPI adapter that
 * validates inputs, hops to the global region scheduler (a Folia
 * requirement for any world-registration call), and bridges to / from
 * {@link CompletableFuture}.
 *
 * <p><b>Maintenance note:</b> the factory's reflection is pinned by
 * signature to Paper 1.21.x internals (specifically {@code ServerLevel}'s
 * 16-argument constructor, {@code PaperWorldLoader.loadWorldData},
 * {@code FeatureHooks.tickEntityManager}, and a small handful of helper
 * APIs). Any of these can change between Paper builds; when they do, the
 * factory throws a descriptive exception and this class fails the future.
 * The caller in {@code MultiverseService} surfaces the error to operators
 * without falling back to a pending-restart path.
 *
 * @author JExcellence
 * @since 3.5.0
 */
public final class FoliaRuntimeWorldLoader implements RuntimeWorldLoader {

    /** {@inheritDoc} */
    @Override
    public @NotNull String backendId() {
        return "folia-nms";
    }

    /**
     * Loads a world on Folia by:
     * <ol>
     *   <li>asserting the world directory exists with a {@code level.dat};</li>
     *   <li>short-circuiting if Bukkit already has the world registered;</li>
     *   <li>scheduling the actual registration on Folia's global region
     *       (the only thread that may add to {@code MinecraftServer}'s
     *       levels map);</li>
     *   <li>delegating to {@link FoliaNmsWorldFactory#registerWorld} for
     *       the reflective ServerLevel construction + {@code addLevel}.</li>
     * </ol>
     *
     * <p>On Paper (no Folia patch) we still go through the same path —
     * the global-region scheduler degrades to {@code Bukkit.getScheduler()}
     * via the runtime probe.
     */
    @Override
    public @NotNull CompletableFuture<World> loadWorld(@NotNull String worldName,
                                                        World.@NotNull Environment environment)
            throws IOException {
        final File worldDir = new File(Bukkit.getWorldContainer(), worldName).getAbsoluteFile();
        if (!new File(worldDir, "level.dat").isFile()) {
            throw new IOException("level.dat missing for world '" + worldName
                    + "' — caller must write the skeleton before invoking the loader");
        }
        final World already = Bukkit.getWorld(worldName);
        if (already != null) {
            return CompletableFuture.completedFuture(already);
        }

        final CompletableFuture<World> result = new CompletableFuture<>();
        runOnGlobalRegion(() -> {
            try {
                final World loaded = FoliaNmsWorldFactory.registerWorld(worldName, environment);
                result.complete(loaded);
            } catch (final Throwable ex) {
                // Wrap in a top-level exception so the caller's exceptionally
                // handler doesn't have to unwrap reflection-specific cause
                // chains to read a sensible message.
                result.completeExceptionally(new RuntimeException(
                        "NMS world registration failed for '" + worldName + "': " + ex.getMessage(), ex));
            }
        });
        return result;
    }

    /**
     * Posts a task to Folia's {@code GlobalRegionScheduler} via reflection.
     * Reflection avoids hard-binding this module to folialib at compile
     * time; on plain Paper (no Folia patch) we fall back to inline run.
     */
    private static void runOnGlobalRegion(@NotNull Runnable task) {
        try {
            final Server server = Bukkit.getServer();
            final Method getGlobalRegionScheduler = server.getClass()
                    .getMethod("getGlobalRegionScheduler");
            final Object scheduler = getGlobalRegionScheduler.invoke(server);

            // Need a Plugin reference for the scheduler's execute signature.
            // Pick the JExMultiverse plugin instance if present; any loaded
            // plugin works for the scheduler binding.
            org.bukkit.plugin.Plugin owner = null;
            for (final var p : Bukkit.getPluginManager().getPlugins()) {
                if (p.getName().startsWith("JExMultiverse")) {
                    owner = p;
                    break;
                }
            }
            if (owner == null && Bukkit.getPluginManager().getPlugins().length > 0) {
                owner = Bukkit.getPluginManager().getPlugins()[0];
            }
            if (owner == null) {
                task.run();
                return;
            }
            final Method execute = scheduler.getClass()
                    .getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class);
            execute.invoke(scheduler, owner, task);
        } catch (final NoSuchMethodException notFolia) {
            // Not Folia — the caller decides whether they need region-thread
            // affinity for the underlying createWorld. We just inline.
            task.run();
        } catch (final Throwable ex) {
            throw new IllegalStateException("Failed to schedule world load on global region", ex);
        }
    }

    /**
     * Static probe so callers can decide before they construct this
     * loader whether the running server is actually Folia.
     */
    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (final ClassNotFoundException notFolia) {
            return false;
        }
    }
}
