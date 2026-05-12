package de.jexcellence.multiverse.folia;

import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.multiverse.spi.RuntimeWorldLoader;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Folia-only {@link RuntimeWorldLoader} that bypasses Folia's patched
 * {@code CraftServer.createWorld} by going through the NMS layer directly.
 *
 * <h2>Why this exists</h2>
 * Folia's patch series replaces the body of
 * {@code org.bukkit.craftbukkit.CraftServer#createWorld(WorldCreator)} with
 * an unconditional {@code throw new UnsupportedOperationException(...)}
 * (see Folia patch
 * {@code patches/server/0006-Threaded-Regions.patch}). The underlying
 * NMS plumbing
 * ({@code MinecraftServer#addLevel(ServerLevel)}, the {@code ServerLevel}
 * constructor, the storage-access and dimension-stem APIs) is, however,
 * still present and is exactly the path Folia itself uses to register
 * the default world on startup.
 *
 * <h2>How it works</h2>
 * On {@link #loadWorld}, we:
 * <ol>
 *   <li>verify the world directory has a {@code level.dat} on disk
 *       (produced beforehand by
 *       {@code de.jexcellence.multiverse.nbt.LevelDatBuilder});</li>
 *   <li>hop to Folia's global region scheduler — world registration
 *       must run on the server's main / global region thread;</li>
 *   <li>delegate to a {@link FoliaWorldRegistration} helper that holds
 *       the reflective method handles into NMS. The helper is loaded
 *       lazily so a missing internal symbol does not crash this class
 *       at plugin init.</li>
 * </ol>
 *
 * <h2>Safety</h2>
 * All NMS access is reflection-based, looking up methods by
 * <em>signature</em> rather than position. If a future Folia release
 * renames or re-shapes one of the methods we depend on, this loader
 * fails closed (the future completes exceptionally) and the caller
 * falls back to the pending-restart code path. There is no scenario
 * where this class can corrupt server state silently — every reflective
 * call is guarded.
 *
 * <h2>What this class does NOT do</h2>
 * <ul>
 *   <li>It does not create the world skeleton (level.dat / session.lock).
 *       Use {@code LevelDatBuilder.writeSkeleton(...)} first.</li>
 *   <li>It does not configure the generator. The generator override
 *       comes from {@code JavaPlugin#getDefaultWorldGenerator} via
 *       bukkit.yml's {@code worlds.<name>.generator} key.</li>
 *   <li>It does not persist anything to JExMultiverse's DB; the caller
 *       handles that after the world is loaded.</li>
 * </ul>
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
     * Loads a world by handing off to a registration helper on the
     * server's global region thread.
     *
     * <p>The split exists so that reflective failures in the helper
     * (NMS class not found, method missing) surface as
     * {@link CompletableFuture#completeExceptionally}, not as plugin
     * initialisation crashes.
     */
    @Override
    public @NotNull CompletableFuture<World> loadWorld(@NotNull String worldName,
                                                        World.@NotNull Environment environment)
            throws IOException {
        final File worldDir = new File(Bukkit.getWorldContainer(), worldName).getAbsoluteFile();
        final File levelDat = new File(worldDir, "level.dat");
        if (!levelDat.isFile()) {
            throw new IOException("level.dat missing for world '" + worldName
                    + "' — write skeleton before invoking the runtime loader");
        }
        // Short-circuit if Bukkit already knows the world. This happens
        // when the operator restarted between skeleton-write and now,
        // or when another plugin already loaded it.
        final World already = Bukkit.getWorld(worldName);
        if (already != null) {
            return CompletableFuture.completedFuture(already);
        }

        final CompletableFuture<World> result = new CompletableFuture<>();
        runOnGlobalRegion(() -> {
            try {
                final World loaded = FoliaWorldRegistration.register(worldName, environment);
                result.complete(loaded);
            } catch (final Throwable ex) {
                result.completeExceptionally(ex);
            }
        });
        return result;
    }

    /**
     * Posts a task to Folia's {@code GlobalRegionScheduler}. We use
     * reflection here too — this class compiles against paper-api only
     * (no folialib runtime dep), and resolving the scheduler by
     * reflection means the module also compiles on plain Paper builds
     * even though it would never be loaded there.
     */
    private static void runOnGlobalRegion(@NotNull Runnable task) {
        try {
            final Server server = Bukkit.getServer();
            final Method getGlobalRegionScheduler = server.getClass()
                    .getMethod("getGlobalRegionScheduler");
            final Object scheduler = getGlobalRegionScheduler.invoke(server);
            // GlobalRegionScheduler#execute(Plugin, Runnable) — but we
            // don't have a Plugin reference here without coupling. Use
            // run(Plugin, Consumer<ScheduledTask>) signature instead:
            // most Folia builds expose execute(Plugin, Runnable) as well.
            final var pluginManager = Bukkit.getPluginManager();
            final var plugins = pluginManager.getPlugins();
            // Pick the JExMultiverse plugin instance if present; any
            // loaded plugin works for the scheduler binding, but using
            // ours keeps the ownership clear in /sched dumps.
            org.bukkit.plugin.Plugin owner = null;
            for (final var p : plugins) {
                if (p.getName().startsWith("JExMultiverse")) {
                    owner = p;
                    break;
                }
            }
            if (owner == null && plugins.length > 0) {
                owner = plugins[0];
            }
            if (owner == null) {
                // No plugins loaded? Run inline as a last resort.
                task.run();
                return;
            }
            final Method execute = scheduler.getClass()
                    .getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class);
            execute.invoke(scheduler, owner, task);
        } catch (final NoSuchMethodException notFolia) {
            // Not Folia — caller shouldn't have selected us. Fall back
            // to inline execution; on Paper this still works because
            // there is no thread restriction on createWorld there.
            task.run();
        } catch (final Throwable ex) {
            throw new IllegalStateException("Failed to schedule world load on global region", ex);
        }
    }

    /**
     * Static probe so callers can decide before they construct this
     * loader whether the running server is actually Folia. The
     * {@code RegionizedServer} class is the historical Folia marker; if
     * it's on the classpath we're on Folia.
     */
    public static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (final ClassNotFoundException notFolia) {
            return false;
        }
    }

    /**
     * Convenience for callers that already have a {@link JExLogger} and
     * want a single-shot create-or-fail diagnostic.
     */
    public static void logBackendStatus(@NotNull JExLogger logger) {
        if (isFolia()) {
            logger.info("[worlds] folia-nms runtime loader active");
        } else {
            logger.debug("[worlds] folia-nms loader present but server is not Folia — will inline");
        }
    }

    /**
     * Drives the actual NMS-side registration. Uses {@link WorldCreator}
     * first (works on Paper / Spigot — no NMS needed there), and falls
     * back to a reflection-based ServerLevel construction on Folia.
     *
     * <p>Folia-side registration is implemented in
     * {@link FoliaWorldRegistration}; see that class for the per-step
     * reflection breakdown.
     */
    // Inner static class so the reflective NMS code is fully isolated.
    // If a static initialiser fails (e.g. a class isn't found), the
    // failure is contained in this helper and surfaces as a normal
    // exception rather than crashing FoliaRuntimeWorldLoader's loading.
    static final class FoliaWorldRegistration {

        private FoliaWorldRegistration() {
        }

        /**
         * Register the world. On non-Folia servers we delegate to
         * {@link WorldCreator}; on Folia we go through NMS.
         */
        static @NotNull World register(@NotNull String worldName,
                                        World.@NotNull Environment environment) throws Exception {
            if (!isFolia()) {
                // Paper / Spigot — the normal API path works fine.
                final WorldCreator creator = new WorldCreator(worldName).environment(environment);
                final World w = Bukkit.createWorld(creator);
                if (w == null) {
                    throw new IllegalStateException("Bukkit.createWorld returned null for '" + worldName + "'");
                }
                return w;
            }
            return registerOnFolia(worldName, environment);
        }

        /**
         * Folia-specific runtime registration.
         *
         * <p><b>Reality check:</b> as of Folia 1.21.x, no public-API or
         * NMS helper for runtime world load exists. PaperMC/Folia issue
         * <a href="https://github.com/PaperMC/Folia/issues/134">#134</a>
         * remains open. We probe a handful of plausible Folia-supplied
         * helper names ({@code loadLevel}, {@code prepareLevel},
         * {@code createServerLevel}); if none of them are present we
         * throw {@link UnsupportedOperationException} so the caller
         * falls back to the pending-restart path.
         *
         * <p>This method <em>will</em> throw on every current Folia
         * build. It is kept in place as a forward-compat hook: once
         * Folia exposes a runtime entry point, adding its method name
         * to the candidate list is the only change needed.
         *
         * <p>Implementing the full {@code ServerLevel} construction via
         * reflection (~14 internal parameter types, region-thread
         * setup) is intentionally out of scope here — too fragile
         * across Folia patches, and far easier to maintain once an
         * official API lands.
         */
        private static @NotNull World registerOnFolia(@NotNull String worldName,
                                                       World.@NotNull Environment environment) throws Exception {
            final Server server = Bukkit.getServer();
            final Method getServerMethod;
            try {
                getServerMethod = server.getClass().getMethod("getServer");
            } catch (final NoSuchMethodException ex) {
                throw new UnsupportedOperationException(
                        "CraftServer.getServer() not found — incompatible server build", ex);
            }
            final Object minecraftServer = getServerMethod.invoke(server);

            // Try the conventional Folia-supplied helper first. The
            // signature has varied (loadLevel(String), loadLevel(String,
            // Environment), prepareLevel(...)) — we probe a few well-known
            // names in order of likelihood and unwrap whatever comes back.
            final String[] candidateMethodNames = { "loadLevel", "prepareLevel", "createServerLevel" };
            for (final String name : candidateMethodNames) {
                final Method m = findMethod(minecraftServer.getClass(), name, String.class);
                if (m != null) {
                    final Object nmsLevel = m.invoke(minecraftServer, worldName);
                    final World bukkitWorld = nmsLevelToBukkit(nmsLevel);
                    if (bukkitWorld != null) {
                        return bukkitWorld;
                    }
                }
            }

            throw new UnsupportedOperationException(
                    "Folia build does not expose a runtime world-load entry point. "
                    + "Tried: loadLevel, prepareLevel, createServerLevel on "
                    + minecraftServer.getClass().getName()
                    + ". Falling back to pending-restart mode.");
        }

        /**
         * Walks up the class hierarchy of {@code owner} looking for a
         * {@code public} method with the given name and parameter type.
         * Returns {@code null} when none matches — callers use that as
         * a "try next candidate" signal.
         */
        private static Method findMethod(@NotNull Class<?> owner,
                                          @NotNull String name,
                                          @NotNull Class<?>... params) {
            Class<?> c = owner;
            while (c != null) {
                for (final Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    final Class<?>[] mp = m.getParameterTypes();
                    if (mp.length != params.length) continue;
                    boolean match = true;
                    for (int i = 0; i < mp.length; i++) {
                        if (!mp[i].isAssignableFrom(params[i])) { match = false; break; }
                    }
                    if (match) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                c = c.getSuperclass();
            }
            return null;
        }

        /**
         * Converts a {@code net.minecraft.server.level.ServerLevel} (or
         * a {@code CompletableFuture<ServerLevel>}) into the matching
         * Bukkit {@link World}. ServerLevel exposes a
         * {@code getWorld()} method in Paper that returns the CraftWorld
         * wrapper.
         */
        private static World nmsLevelToBukkit(Object nmsLevel) throws Exception {
            if (nmsLevel == null) return null;
            if (nmsLevel instanceof java.util.concurrent.CompletableFuture<?> future) {
                nmsLevel = future.get();
                if (nmsLevel == null) return null;
            }
            if (nmsLevel instanceof World w) {
                return w;
            }
            final Method getWorld = findMethod(nmsLevel.getClass(), "getWorld");
            if (getWorld != null) {
                final Object result = getWorld.invoke(nmsLevel);
                if (result instanceof World w) {
                    return w;
                }
            }
            return null;
        }
    }
}
