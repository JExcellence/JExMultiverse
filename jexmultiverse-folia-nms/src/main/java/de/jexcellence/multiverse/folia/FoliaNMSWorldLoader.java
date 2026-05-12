package de.jexcellence.multiverse.folia;

import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.spi.RuntimeWorldLoader;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Folia-specific {@link RuntimeWorldLoader} implementation that creates companion worlds
 * using the parent ServerLevel's companion world creation mechanism.
 * <p>
 * This loader specifically handles NORMAL environment companion worlds on Folia by:
 * <ul>
 *   <li>Locating the parent ServerLevel (default world)</li>
 *   <li>Invoking the parent's companion world creation method via NMS</li>
 *   <li>Applying the appropriate ChunkGenerator based on MVWorldType</li>
 *   <li>Registering the companion world with MinecraftServer</li>
 * </ul>
 * <p>
 * This implementation uses reflection to access NMS methods, ensuring compatibility
 * across different Folia versions. If NMS methods are not available, the loader
 * fails gracefully and allows fallback to the pending-restart path.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class FoliaNMSWorldLoader implements RuntimeWorldLoader {

    private final GeneratorResolver generatorResolver;

    /**
     * Creates a new FoliaNMSWorldLoader with the specified generator resolver.
     *
     * @param generatorResolver the resolver for determining chunk generators based on world type
     */
    public FoliaNMSWorldLoader(@NotNull GeneratorResolver generatorResolver) {
        this.generatorResolver = generatorResolver;
    }

    /**
     * Creates a new FoliaNMSWorldLoader with no generator resolver.
     * Worlds will be created with default (vanilla) generation.
     */
    public FoliaNMSWorldLoader() {
        this.generatorResolver = null;
    }

    @Override
    public @NotNull String backendId() {
        return "folia-nms";
    }

    @Override
    public @NotNull CompletableFuture<World> loadWorld(@NotNull String worldName,
                                                        World.@NotNull Environment environment)
            throws IOException {
        // Validate level.dat exists
        final File worldDir = new File(Bukkit.getWorldContainer(), worldName).getAbsoluteFile();
        final File levelDat = new File(worldDir, "level.dat");
        if (!levelDat.isFile()) {
            throw new IOException("level.dat not found for world: " + worldName
                    + " — write skeleton before invoking the runtime loader");
        }

        // Short-circuit if Bukkit already knows the world
        final World already = Bukkit.getWorld(worldName);
        if (already != null) {
            return CompletableFuture.completedFuture(already);
        }

        final CompletableFuture<World> result = new CompletableFuture<>();
        runOnGlobalRegion(() -> {
            try {
                // Resolve the chunk generator for this world
                final ChunkGenerator generator = resolveGenerator(worldName);
                
                // Register the world via NMS
                final World loaded = FoliaNMSRegistration.register(worldName, environment, generator);
                result.complete(loaded);
            } catch (final UnsupportedOperationException ex) {
                // NMS methods not available — this is expected on some Folia versions
                result.completeExceptionally(new IOException(
                        "Folia NMS world loading not available: " + ex.getMessage(), ex));
            } catch (final ReflectiveOperationException ex) {
                // Reflection failed — NMS structure changed
                result.completeExceptionally(new IOException(
                        "NMS reflection failed for world '" + worldName + "': " + ex.getMessage(), ex));
            } catch (final Throwable ex) {
                // Unexpected error during world registration
                result.completeExceptionally(new IOException(
                        "Failed to register world '" + worldName + "' via NMS: " + ex.getMessage(), ex));
            }
        });
        return result;
    }

    /**
     * Resolves the chunk generator for the given world name.
     * <p>
     * If a generator resolver is configured, it will be used to determine the generator
     * based on the world's MVWorldType. Otherwise, returns null for default generation.
     *
     * @param worldName the name of the world
     * @return the chunk generator, or null for default generation
     */
    private @Nullable ChunkGenerator resolveGenerator(@NotNull String worldName) {
        if (generatorResolver == null) {
            return null;
        }
        return generatorResolver.resolveGenerator(worldName);
    }

    /**
     * Posts a task to Folia's {@code GlobalRegionScheduler}.
     * <p>
     * Uses reflection to avoid compile-time dependency on Folia-specific classes.
     * Falls back to inline execution on non-Folia servers.
     *
     * @param task the task to execute on the global region
     */
    private static void runOnGlobalRegion(@NotNull Runnable task) {
        try {
            final var server = Bukkit.getServer();
            final Method getGlobalRegionScheduler = server.getClass()
                    .getMethod("getGlobalRegionScheduler");
            final Object scheduler = getGlobalRegionScheduler.invoke(server);

            // Find a plugin to own the scheduled task
            final var pluginManager = Bukkit.getPluginManager();
            final var plugins = pluginManager.getPlugins();
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
                // No plugins loaded — run inline as last resort
                task.run();
                return;
            }

            final Method execute = scheduler.getClass()
                    .getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class);
            execute.invoke(scheduler, owner, task);
        } catch (final NoSuchMethodException notFolia) {
            // Not Folia — fall back to inline execution
            task.run();
        } catch (final Throwable ex) {
            throw new IllegalStateException("Failed to schedule world load on global region", ex);
        }
    }

    /**
     * Functional interface for resolving chunk generators based on world name.
     * <p>
     * Implementations should look up the world's MVWorldType and return the
     * appropriate generator (VoidChunkGenerator, PlotChunkGenerator, or null for default).
     */
    @FunctionalInterface
    public interface GeneratorResolver {
        /**
         * Resolves the chunk generator for the given world name.
         *
         * @param worldName the name of the world
         * @return the chunk generator, or null for default generation
         */
        @Nullable ChunkGenerator resolveGenerator(@NotNull String worldName);
    }

    /**
     * Inner class that handles the actual NMS-side registration.
     * <p>
     * Isolated in a separate class so reflective failures don't crash the loader
     * during initialization.
     */
    static final class FoliaNMSRegistration {

        private FoliaNMSRegistration() {
        }

        /**
         * Registers a world via NMS, applying the specified chunk generator.
         *
         * @param worldName   the name of the world to register
         * @param environment the Bukkit environment
         * @param generator   the chunk generator, or null for default generation
         * @return the registered Bukkit World
         * @throws Exception if registration fails
         */
        static @NotNull World register(@NotNull String worldName,
                                        World.@NotNull Environment environment,
                                        @Nullable ChunkGenerator generator) throws Exception {
            if (!isFolia()) {
                // Paper/Spigot — use the normal API path
                final var creator = new org.bukkit.WorldCreator(worldName)
                        .environment(environment);
                if (generator != null) {
                    creator.generator(generator);
                }
                final World w = Bukkit.createWorld(creator);
                if (w == null) {
                    throw new IllegalStateException("Bukkit.createWorld returned null for '" + worldName + "'");
                }
                return w;
            }
            return registerOnFolia(worldName, environment, generator);
        }

        /**
         * Folia-specific NMS registration path.
         * <p>
         * For companion worlds (NORMAL environment on Folia), this method attempts to:
         * <ol>
         *   <li>Get the parent ServerLevel (default world)</li>
         *   <li>Invoke companion world creation via the parent</li>
         *   <li>Apply the specified chunk generator</li>
         *   <li>Register the world with MinecraftServer</li>
         * </ol>
         * <p>
         * Falls back to Folia's standard world loading methods if companion creation
         * is not available or fails.
         *
         * @param worldName   the name of the world
         * @param environment the Bukkit environment
         * @param generator   the chunk generator, or null for default
         * @return the registered Bukkit World
         * @throws Exception if registration fails
         */
        private static @NotNull World registerOnFolia(@NotNull String worldName,
                                                       World.@NotNull Environment environment,
                                                       @Nullable ChunkGenerator generator) throws Exception {
            final var server = Bukkit.getServer();
            final Method getServerMethod;
            try {
                getServerMethod = server.getClass().getMethod("getServer");
            } catch (final NoSuchMethodException ex) {
                throw new UnsupportedOperationException(
                        "CraftServer.getServer() not found — incompatible server build", ex);
            }
            final Object minecraftServer = getServerMethod.invoke(server);

            // For NORMAL environment, attempt companion world creation via parent ServerLevel
            if (environment == World.Environment.NORMAL) {
                try {
                    final World companionWorld = createCompanionWorld(minecraftServer, worldName, generator);
                    if (companionWorld != null) {
                        return companionWorld;
                    }
                } catch (final Exception ex) {
                    // Companion creation failed, fall through to standard loading
                    // This is expected if the method doesn't exist or if there's an issue
                }
            }

            // Fall back to Folia's standard world loading methods
            final String[] candidateMethodNames = {"loadLevel", "prepareLevel", "createServerLevel"};
            for (final String name : candidateMethodNames) {
                final Method m = findMethod(minecraftServer.getClass(), name, String.class);
                if (m != null) {
                    final Object nmsLevel = m.invoke(minecraftServer, worldName);
                    final World bukkitWorld = nmsLevelToBukkit(nmsLevel);
                    if (bukkitWorld != null) {
                        // Note: Generator must be set via bukkit.yml for this path
                        // The generator parameter is only used for companion creation above
                        return bukkitWorld;
                    }
                }
            }

            throw new UnsupportedOperationException(
                    "Folia build does not expose a runtime world-load entry point. "
                            + "Tried: companion creation, loadLevel, prepareLevel, createServerLevel on "
                            + minecraftServer.getClass().getName()
                            + ". Falling back to pending-restart mode.");
        }

        /**
         * Attempts to create a companion world via the parent ServerLevel.
         * <p>
         * This method tries to locate the parent ServerLevel (default world) and
         * invoke its companion world creation method. The exact method name and
         * signature may vary across Folia versions, so we try multiple approaches.
         *
         * @param minecraftServer the MinecraftServer instance
         * @param worldName       the name of the companion world
         * @param generator       the chunk generator, or null for default
         * @return the created Bukkit World, or null if companion creation is not available
         * @throws Exception if reflection or NMS operations fail
         */
        private static @Nullable World createCompanionWorld(@NotNull Object minecraftServer,
                                                             @NotNull String worldName,
                                                             @Nullable ChunkGenerator generator) throws Exception {
            // Get the default world (parent for NORMAL companions)
            final World defaultWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (defaultWorld == null) {
                throw new IllegalStateException("No default world available for companion world creation");
            }

            // Get the parent ServerLevel via CraftWorld.getHandle()
            final Method getHandleMethod = findMethod(defaultWorld.getClass(), "getHandle");
            if (getHandleMethod == null) {
                throw new NoSuchMethodException("CraftWorld.getHandle() not found — cannot access NMS ServerLevel");
            }
            final Object parentLevel = getHandleMethod.invoke(defaultWorld);
            if (parentLevel == null) {
                throw new IllegalStateException("Parent ServerLevel is null");
            }

            // Try to find and invoke companion world creation method
            // Possible method names: createCompanionWorld, addCompanionWorld, createCompanion
            final String[] companionMethodNames = {"createCompanionWorld", "addCompanionWorld", "createCompanion"};
            
            Exception lastException = null;
            for (final String methodName : companionMethodNames) {
                try {
                    // Try with just world name
                    Method companionMethod = findMethod(parentLevel.getClass(), methodName, String.class);
                    if (companionMethod != null) {
                        final Object result = companionMethod.invoke(parentLevel, worldName);
                        final World bukkitWorld = nmsLevelToBukkit(result);
                        if (bukkitWorld != null) {
                            return bukkitWorld;
                        }
                    }

                    // Try with world name and dimension type
                    // Note: We'd need to resolve the dimension type class, which is complex
                    // For now, we rely on the simpler single-parameter version
                } catch (final Exception ex) {
                    lastException = ex;
                    // Continue trying other method names
                }
            }

            // Companion creation method not found or all attempts failed
            if (lastException != null) {
                throw new NoSuchMethodException(
                        "Companion world creation methods not found or failed. Tried: "
                                + String.join(", ", companionMethodNames)
                                + ". Last error: " + lastException.getMessage());
            }
            return null;
        }

        /**
         * Checks if the server is running Folia.
         *
         * @return true if Folia is detected, false otherwise
         */
        private static boolean isFolia() {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                return true;
            } catch (final ClassNotFoundException notFolia) {
                return false;
            }
        }

        /**
         * Finds a method by name and parameter types, walking up the class hierarchy.
         *
         * @param owner  the class to search
         * @param name   the method name
         * @param params the parameter types
         * @return the method, or null if not found
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
                        if (!mp[i].isAssignableFrom(params[i])) {
                            match = false;
                            break;
                        }
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
         * Converts an NMS ServerLevel to a Bukkit World.
         *
         * @param nmsLevel the NMS level object
         * @return the Bukkit World, or null if conversion fails
         * @throws Exception if reflection fails
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
