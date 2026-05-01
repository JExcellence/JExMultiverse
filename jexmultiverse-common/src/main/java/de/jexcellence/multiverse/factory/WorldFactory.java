package de.jexcellence.multiverse.factory;

import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.config.PlotWorldConfig;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.database.repository.MVWorldRepository;
import de.jexcellence.multiverse.generator.plot.PlotChunkGenerator;
import de.jexcellence.multiverse.generator.void_world.VoidChunkGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory responsible for creating, loading, caching, unloading, and deleting
 * Bukkit worlds managed by JExMultiverse.
 *
 * <p>Uses constructor injection — no static singleton.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class WorldFactory {

    private final JavaPlugin plugin;
    private final MVWorldRepository repository;
    private final JExLogger logger;

    private final ChunkGenerator voidGenerator;
    private final ChunkGenerator plotGenerator;
    private final PlotWorldConfig plotConfig;

    private final Map<String, MVWorld> worldCache = new ConcurrentHashMap<>();

    public WorldFactory(@NotNull JavaPlugin plugin,
                        @NotNull MVWorldRepository repository,
                        @NotNull JExLogger logger) {
        this.plugin = plugin;
        this.repository = repository;
        this.logger = logger;
        this.voidGenerator = new VoidChunkGenerator();
        this.plotConfig = PlotWorldConfig.load(plugin.getDataFolder());
        this.plotGenerator = new PlotChunkGenerator(
                plotConfig.plotSize(),
                plotConfig.roadWidth(),
                plotConfig.plotHeight(),
                plotConfig.roadMaterial(),
                plotConfig.wallMaterial(),
                plotConfig.layers());
        logger.info("Plot generator: plot={}, road={}, height={}, road={}, wall={}, layers={}",
                plotConfig.plotSize(), plotConfig.roadWidth(), plotConfig.plotHeight(),
                plotConfig.roadMaterial(), plotConfig.wallMaterial(), plotConfig.layers().size());
    }

    /** Returns the plot-world generation config currently in effect. */
    public @NotNull PlotWorldConfig plotConfig() {
        return plotConfig;
    }

    // ── World creation ──────────────────────────────────────────────────────────

    /**
     * Creates a Bukkit world with the given parameters and returns it.
     *
     * @param name        the world folder name
     * @param environment the world environment
     * @param type        the multiverse world type
     * @return the created Bukkit world, or {@code null} on failure
     */
    public @Nullable World createBukkitWorld(@NotNull String name,
                                              World.@NotNull Environment environment,
                                              @NotNull MVWorldType type) {
        try {
            var creator = new WorldCreator(name)
                    .environment(environment);

            var generator = getGeneratorForType(type);
            if (generator != null) {
                creator.generator(generator);
            }

            var world = creator.createWorld();
            if (world != null) {
                // Free spawn chunks once creation completes — vanilla worldgen
                // forcibly loads spawn chunks during create which costs memory
                // and tick time after creation. Custom generators (VOID/PLOT)
                // benefit too since their spawn chunks contain little of value.
                world.setKeepSpawnInMemory(false);
                logger.info("Created Bukkit world '{}' (env={}, type={})", name, environment, type);
            }
            return world;
        } catch (Exception e) {
            logger.error("Failed to create Bukkit world '{}'", name, e);
            return null;
        }
    }

    /**
     * Returns the chunk generator for the given world type.
     *
     * @param type the multiverse world type
     * @return the chunk generator, or {@code null} for vanilla generation
     */
    public @Nullable ChunkGenerator getGeneratorForType(@NotNull MVWorldType type) {
        return switch (type) {
            case VOID -> voidGenerator;
            case PLOT -> plotGenerator;
            case DEFAULT -> null;
        };
    }

    /**
     * Returns the default spawn location for a newly created world of the given type.
     *
     * @param world the Bukkit world
     * @param type  the multiverse world type
     * @return the spawn location
     */
    public @NotNull Location getDefaultSpawnForType(@NotNull World world, @NotNull MVWorldType type) {
        return switch (type) {
            case VOID -> new Location(world, 0.5, 65, 0.5, 0, 0);
            case PLOT -> new Location(world, 0.5, 65, 0.5, 0, 0);
            case DEFAULT -> world.getSpawnLocation();
        };
    }

    // ── World loading ───────────────────────────────────────────────────────────

    /**
     * Loads all worlds from the database into Bukkit and caches them.
     *
     * @return a future that completes when all worlds are loaded
     */
    public @NotNull CompletableFuture<Void> loadAllWorlds() {
        return repository.findAllAsync().thenAccept(worlds -> {
            for (var mvWorld : worlds) {
                Bukkit.getScheduler().runTask(plugin, () -> loadWorld(mvWorld));
            }
            logger.info("Queued {} worlds for loading", worlds.size());
        }).exceptionally(ex -> {
            logger.error("Failed to load worlds from database", ex);
            return null;
        });
    }

    /**
     * Loads a single MVWorld into Bukkit, creating the world if it does not exist.
     *
     * @param mvWorld the world entity to load
     * @return the loaded Bukkit world, or {@code null} on failure
     */
    public @Nullable World loadWorld(@NotNull MVWorld mvWorld) {
        var existing = Bukkit.getWorld(mvWorld.getIdentifier());
        if (existing != null) {
            cacheWorld(mvWorld);
            logger.debug("World '{}' already loaded in Bukkit", mvWorld.getIdentifier());
            return existing;
        }

        var world = createBukkitWorld(mvWorld.getIdentifier(), mvWorld.getEnvironment(), mvWorld.getType());
        if (world != null) {
            cacheWorld(mvWorld);
            logger.info("Loaded world '{}'", mvWorld.getIdentifier());
        } else {
            logger.warn("Failed to load world '{}'", mvWorld.getIdentifier());
        }
        return world;
    }

    // ── World unloading ─────────────────────────────────────────────────────────

    /**
     * Unloads a world from Bukkit and removes it from the cache.
     *
     * @param identifier the world name
     * @param save       whether to save chunks before unloading
     * @return {@code true} if the world was unloaded
     */
    public boolean unloadWorld(@NotNull String identifier, boolean save) {
        var world = Bukkit.getWorld(identifier);
        if (world == null) {
            invalidateCache(identifier);
            return true;
        }

        // Teleport all players out before unloading
        var defaultWorld = Bukkit.getWorlds().getFirst();
        for (var player : world.getPlayers()) {
            player.teleport(defaultWorld.getSpawnLocation());
        }

        var success = Bukkit.unloadWorld(world, save);
        if (success) {
            invalidateCache(identifier);
            logger.info("Unloaded world '{}'", identifier);
        } else {
            logger.warn("Failed to unload world '{}'", identifier);
        }
        return success;
    }

    // ── World deletion ──────────────────────────────────────────────────────────

    /**
     * Deletes the world folder from disk using {@link Files#walk}.
     *
     * @param worldName the world folder name
     * @return a future that completes when the files are deleted
     */
    public @NotNull CompletableFuture<Boolean> deleteWorldFiles(@NotNull String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            var worldPath = Path.of(Bukkit.getWorldContainer().getAbsolutePath(), worldName);
            if (!Files.exists(worldPath)) {
                logger.debug("World folder '{}' does not exist, nothing to delete", worldName);
                return true;
            }
            try (var walk = Files.walk(worldPath)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                logger.warn("Failed to delete file: {}", path);
                            }
                        });
                logger.info("Deleted world folder '{}'", worldName);
                return true;
            } catch (IOException e) {
                logger.error("Failed to walk world directory '{}'", worldName, e);
                return false;
            }
        });
    }

    // ── Cache operations ────────────────────────────────────────────────────────

    /**
     * Returns a cached world by its identifier.
     *
     * @param identifier the world name
     * @return the cached world, or empty if not found
     */
    public @NotNull Optional<MVWorld> getCachedWorld(@NotNull String identifier) {
        return Optional.ofNullable(worldCache.get(identifier));
    }

    /**
     * Adds or replaces a world in the cache.
     *
     * @param world the world to cache
     */
    public void cacheWorld(@NotNull MVWorld world) {
        worldCache.put(world.getIdentifier(), world);
    }

    /**
     * Removes a world from the cache.
     *
     * @param identifier the world name
     */
    public void invalidateCache(@NotNull String identifier) {
        worldCache.remove(identifier);
    }

    /**
     * Clears the entire world cache.
     */
    public void clearCache() {
        worldCache.clear();
    }

    /**
     * Refreshes the cache from the database.
     *
     * @return a future that completes when the cache is refreshed
     */
    public @NotNull CompletableFuture<Void> refreshCache() {
        return repository.findAllAsync().thenAccept(worlds -> {
            worldCache.clear();
            for (var world : worlds) {
                worldCache.put(world.getIdentifier(), world);
            }
            logger.info("Refreshed world cache ({} entries)", worlds.size());
        }).exceptionally(ex -> {
            logger.error("Failed to refresh world cache", ex);
            return null;
        });
    }

    /**
     * Returns an unmodifiable view of all cached worlds.
     *
     * @return all cached world entities
     */
    public @NotNull Collection<MVWorld> getAllCachedWorlds() {
        return Collections.unmodifiableCollection(worldCache.values());
    }

    // ── Query helpers ───────────────────────────────────────────────────────────

    /**
     * Checks whether a world with the given name is loaded in Bukkit.
     *
     * @param identifier the world name
     * @return {@code true} if the world is loaded
     */
    public boolean isWorldLoaded(@NotNull String identifier) {
        return Bukkit.getWorld(identifier) != null;
    }

    /**
     * Returns the Bukkit world for the given identifier.
     *
     * @param identifier the world name
     * @return the Bukkit world, or empty if not loaded
     */
    public @NotNull Optional<World> getBukkitWorld(@NotNull String identifier) {
        return Optional.ofNullable(Bukkit.getWorld(identifier));
    }

}
