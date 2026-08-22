package de.jexcellence.multiverse.factory;

import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.config.PlotWorldConfig;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.api.event.MVWorldLoadEvent;
import de.jexcellence.multiverse.api.event.MVWorldLoadedEvent;
import de.jexcellence.multiverse.api.event.MVWorldUnloadEvent;
import de.jexcellence.multiverse.api.event.MVWorldUnloadedEvent;
import de.jexcellence.multiverse.database.repository.MVWorldRepository;
import de.jexcellence.multiverse.event.EventDispatch;
import de.jexcellence.multiverse.generator.plot.PlotChunkGenerator;
import de.jexcellence.multiverse.generator.void_world.VoidChunkGenerator;
import de.jexcellence.multiverse.service.SchematicService;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory responsible for creating, loading, caching, unloading, and deleting
 * Bukkit worlds managed by JExMultiverse.
 *
 * <p>Uses constructor injection - no static singleton.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class WorldFactory {

    private final JavaPlugin plugin;
    private final MVWorldRepository repository;
    private final JExLogger logger;
    private final PlatformScheduler scheduler;

    private final ChunkGenerator voidGenerator;
    private final ChunkGenerator plotGenerator;
    private final PlotWorldConfig plotConfig;
    private final SchematicService schematics;

    private final Map<String, MVWorld> worldCache = new ConcurrentHashMap<>();

    /**
     * Files never carried into a cloned world. {@code uid.dat} would duplicate the
     * source world's UUID and make the server refuse to load one of the pair;
     * {@code session.lock} is held by the running server and is recreated on load.
     */
    private static final Set<String> SKIPPED_ON_COPY = Set.of("uid.dat", "session.lock");

    public WorldFactory(@NotNull JavaPlugin plugin,
                        @NotNull MVWorldRepository repository,
                        @NotNull JExLogger logger,
                        @NotNull PlatformScheduler scheduler) {
        this.plugin = plugin;
        this.repository = repository;
        this.logger = logger;
        this.scheduler = scheduler;
        this.voidGenerator = new VoidChunkGenerator();
        this.plotConfig = PlotWorldConfig.load(plugin.getDataFolder());
        this.schematics = new SchematicService(plugin, logger, plotConfig);
        this.plotGenerator = new PlotChunkGenerator(
                plotConfig.plotSize(),
                plotConfig.roadWidth(),
                plotConfig.plotHeight(),
                plotConfig.wallHeight(),
                plotConfig.roadMaterial(),
                plotConfig.wallMaterialUnclaimed(),
                plotConfig.layers(),
                null, null);
        logger.info("Plot generator: plot={}, road={}, height={}, road={}, wall={}, layers={}",
                plotConfig.plotSize(), plotConfig.roadWidth(), plotConfig.plotHeight(),
                plotConfig.roadMaterial(), plotConfig.wallMaterial(), plotConfig.layers().size());
    }

    /** Returns the plot-world generation config currently in effect. */
    public @NotNull PlotWorldConfig plotConfig() {
        return plotConfig;
    }

    /** Returns the shared schematic loader/cache. */
    public @NotNull SchematicService schematics() {
        return schematics;
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
        return createBukkitWorld(name, environment, type, null, null, null);
    }

    /**
     * Creates a Bukkit world honoring per-world plot generation overrides.
     * Pass {@code null} for any override to fall back to the global config.
     *
     * @param name              the world folder name
     * @param environment       the world environment
     * @param type              the multiverse world type
     * @param plotSizeOverride  per-world plot size (PLOT only), or {@code null}
     * @param roadWidthOverride per-world road width (PLOT only), or {@code null}
     * @param schematicName     per-world schematic name (PLOT only), or {@code null}
     * @return the created Bukkit world, or {@code null} on failure
     */
    public @Nullable World createBukkitWorld(@NotNull String name,
                                              World.@NotNull Environment environment,
                                              @NotNull MVWorldType type,
                                              @Nullable Integer plotSizeOverride,
                                              @Nullable Integer roadWidthOverride,
                                              @Nullable String schematicName) {
        try {
            var creator = new WorldCreator(name)
                    .environment(environment);

            var generator = getGeneratorForType(type, plotSizeOverride, roadWidthOverride, schematicName);
            if (generator != null) {
                creator.generator(generator);
            }

            var world = creator.createWorld();
            if (world != null) {
                // Default to not holding spawn chunks resident. A managed world with
                // keepSpawnLoaded set overrides this in applyWorldSettings.
                setIntRule(world, "spawnChunkRadius", 0);
                logger.info("Created Bukkit world '{}' (env={}, type={}, plot-override={}/{}, schematic={})",
                        name, environment, type, plotSizeOverride, roadWidthOverride, schematicName);
            }
            return world;
        } catch (Exception e) {
            logger.error("Failed to create Bukkit world '{}'", name, e);
            return null;
        }
    }

    /**
     * Returns the chunk generator for the given world type using global config.
     */
    public @Nullable ChunkGenerator getGeneratorForType(@NotNull MVWorldType type) {
        return getGeneratorForType(type, null, null, null);
    }

    /**
     * Returns a chunk generator for the given world type, applying optional
     * per-world plot overrides + schematic. For non-PLOT types the overrides
     * are ignored. The shared no-override no-schematic plot generator is
     * reused as a fast path when none of the params are set.
     */
    public @Nullable ChunkGenerator getGeneratorForType(@NotNull MVWorldType type,
                                                         @Nullable Integer plotSizeOverride,
                                                         @Nullable Integer roadWidthOverride,
                                                         @Nullable String schematicName) {
        return switch (type) {
            case VOID -> voidGenerator;
            case PLOT -> {
                if (plotSizeOverride == null && roadWidthOverride == null
                        && (schematicName == null || schematicName.isBlank())) {
                    yield plotGenerator;
                }
                int ps = plotSizeOverride != null ? plotSizeOverride : plotConfig.plotSize();
                int rw = roadWidthOverride != null ? roadWidthOverride : plotConfig.roadWidth();
                yield new PlotChunkGenerator(
                        ps, rw, plotConfig.plotHeight(), plotConfig.wallHeight(),
                        plotConfig.roadMaterial(), plotConfig.wallMaterialUnclaimed(),
                        plotConfig.layers(),
                        schematics, schematicName);
            }
            case DEFAULT -> null;
        };
    }

    /**
     * Returns the effective plot size for the given MVWorld - its override if
     * set, else the global config value. Defined regardless of world type;
     * callers should already know the world is PLOT.
     */
    public int effectivePlotSize(@NotNull MVWorld mv) {
        return mv.getPlotSizeOverride() != null ? mv.getPlotSizeOverride() : plotConfig.plotSize();
    }

    /**
     * Returns the effective road width for the given MVWorld.
     */
    public int effectiveRoadWidth(@NotNull MVWorld mv) {
        return mv.getRoadWidthOverride() != null ? mv.getRoadWidthOverride() : plotConfig.roadWidth();
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
     * After loading persisted worlds, scans for companion worlds that were
     * created at runtime and adopts them into JExMultiverse.
     *
     * @return a future that completes when all worlds are loaded and adopted
     */
    public @NotNull CompletableFuture<Void> loadAllWorlds() {
        return repository.findAllAsync().thenCompose(worlds -> {
            if (worlds.isEmpty()) {
                logger.info("No persisted worlds to load");
                return CompletableFuture.completedFuture(null);
            }
            // Each world creation runs on the appropriate platform thread (main on
            // Paper, global region on Folia) and signals its CompletableFuture when
            // done, so the caller can block until every world is actually in Bukkit
            // and cached. Otherwise services depending on the world cache
            // (PlotService, the protection listeners) start before the worlds exist.
            // Bukkit.getScheduler() throws UOE on Folia - PlatformScheduler.runSync
            // targets GlobalRegionScheduler there.
            logger.info("Loading {} world(s) from database...", worlds.size());
            var futures = new ArrayList<CompletableFuture<Void>>(worlds.size());
            for (var mvWorld : worlds) {
                var f = new CompletableFuture<Void>();
                scheduler.runSync(() -> {
                    try {
                        loadWorld(mvWorld);
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        logger.error("Failed to load world '{}'", mvWorld.getIdentifier(), e);
                    } finally {
                        f.complete(null);
                    }
                });
                futures.add(f);
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenRun(() -> logger.info("All {} world(s) loaded and cached", worlds.size()));
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
            EventDispatch.fire(new MVWorldLoadedEvent(mvWorld.toSnapshot()), scheduler);
            return existing;
        }

        if (EventDispatch.fireSync(new MVWorldLoadEvent(mvWorld.toSnapshot()))) {
            logger.debug("Load of world '{}' cancelled by a listener", mvWorld.getIdentifier());
            return null;
        }

        var world = createBukkitWorld(mvWorld.getIdentifier(), mvWorld.getEnvironment(), mvWorld.getType(),
                mvWorld.getPlotSizeOverride(), mvWorld.getRoadWidthOverride(), mvWorld.getSchematicName());
        if (world != null) {
            cacheWorld(mvWorld);
            applyWorldSettings(world, mvWorld);
            logger.info("Loaded world '{}'", mvWorld.getIdentifier());
            EventDispatch.fire(new MVWorldLoadedEvent(mvWorld.toSnapshot()), scheduler);
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
        return unloadWorld(identifier, save, true);
    }

    /**
     * Unloads a world, optionally without raising the unload events.
     *
     * <p>Deletion passes {@code fireEvents = false}. By the time the delete flow
     * reaches the unload the database row is already gone, so honouring a cancelled
     * {@link MVWorldUnloadEvent} there would leave the world half-deleted. Deletion
     * is vetoed through {@code MVWorldDeleteEvent} instead, and reported through
     * {@code MVWorldDeletedEvent}.
     *
     * @param identifier the world identifier
     * @param save       whether to save chunks before unloading
     * @param fireEvents whether to raise the unload events
     * @return {@code true} if the world was unloaded or was not loaded to begin with
     */
    public boolean unloadWorld(@NotNull String identifier, boolean save, boolean fireEvents) {
        var world = Bukkit.getWorld(identifier);
        if (world == null) {
            invalidateCache(identifier);
            return true;
        }

        // Snapshot before the cache entry is invalidated, so the post-event can still
        // describe what was unloaded. Null when the world has no managed row.
        var snapshot = getCachedWorld(identifier).map(MVWorld::toSnapshot).orElse(null);

        if (fireEvents && EventDispatch.fireSync(new MVWorldUnloadEvent(identifier, snapshot, save))) {
            logger.debug("Unload of world '{}' cancelled by a listener", identifier);
            return false;
        }

        var defaultWorld = Bukkit.getWorlds().getFirst();
        for (var player : world.getPlayers()) {
            player.teleport(defaultWorld.getSpawnLocation());
        }

        var success = Bukkit.unloadWorld(world, save);
        if (success) {
            invalidateCache(identifier);
            logger.info("Unloaded world '{}'", identifier);
            if (fireEvents) {
                EventDispatch.fire(new MVWorldUnloadedEvent(identifier, snapshot, save), scheduler);
            }
        } else {
            logger.warn("Failed to unload world '{}'", identifier);
        }
        return success;
    }

    // ── Per-world runtime settings ──────────────────────────────────────────────

    /**
     * Applies a managed world's persisted runtime settings to the live Bukkit world.
     *
     * <p>Every setting is optional and skipped when unset, so a world that has never
     * been configured behaves exactly as before. Must run on the main thread.
     *
     * @param world   the live Bukkit world
     * @param mvWorld the managed row carrying the settings
     */
    public void applyWorldSettings(@NotNull World world, @NotNull MVWorld mvWorld) {
        // spawnChunkRadius is the modern replacement for the deprecated
        // setKeepSpawnInMemory. 0 keeps nothing resident; 2 is the vanilla default.
        setIntRule(world, "spawnChunkRadius", mvWorld.isKeepSpawnLoaded() ? 2 : 0);
        applyGameRules(world, mvWorld);
        applyDifficulty(world, mvWorld);
        applyTime(world, mvWorld);
        applyWeather(world, mvWorld);
    }

    /**
     * Sets a boolean gamerule by name, resolved through the registry.
     *
     * <p>Avoids the deprecated {@code GameRule} constants; a missing rule is logged
     * rather than thrown, since the server build decides which rules exist.
     *
     * @param world the live Bukkit world
     * @param name  the vanilla gamerule name
     * @param value the value to set
     */
    @SuppressWarnings("unchecked")
    private void setBooleanRule(@NotNull World world, @NotNull String name, boolean value) {
        var rule = resolveGameRule(name);
        if (rule == null || rule.getType() != Boolean.class) {
            logger.warn("Gamerule '{}' unavailable on this server build - skipping", name);
            return;
        }
        world.setGameRule((GameRule<Boolean>) rule, value);
    }

    /**
     * Sets an integer gamerule by name, resolved through the registry.
     *
     * @param world the live Bukkit world
     * @param name  the vanilla gamerule name
     * @param value the value to set
     */
    @SuppressWarnings("unchecked")
    private void setIntRule(@NotNull World world, @NotNull String name, int value) {
        var rule = resolveGameRule(name);
        if (rule == null || rule.getType() != Integer.class) {
            logger.warn("Gamerule '{}' unavailable on this server build - skipping", name);
            return;
        }
        world.setGameRule((GameRule<Integer>) rule, value);
    }

    /**
     * Lazily built index of every gamerule the running server knows, keyed by its
     * lowercased vanilla name. Populated on first use rather than in a static
     * initialiser, because the registry is not usable until the server is up.
     */
    private static volatile Map<String, GameRule<?>> gameRuleIndex;

    /**
     * Returns a gamerule's vanilla name, for example {@code doDaylightCycle}.
     *
     * <p>This is the one place {@code GameRule#getName()} is called. It is deprecated
     * for removal in favour of the {@code Keyed} interface, but the namespaced key
     * format is not something we can verify against the API jar, and guessing it
     * wrong would silently orphan every stored gamerule. When the method is finally
     * removed, change this single helper to derive the name from {@code getKey()} and
     * ship a migration for the stored values.
     *
     * @param rule the gamerule
     * @return the vanilla gamerule name
     */
    @SuppressWarnings("removal")
    public static @NotNull String gameRuleName(@NotNull GameRule<?> rule) {
        return rule.getName();
    }

    /**
     * Resolves a gamerule by its vanilla name, case-insensitively.
     *
     * <p>Goes through {@code Registry.GAME_RULE} rather than the deprecated
     * {@code GameRule.getByName} or the deprecated per-rule constants, so the set of
     * known rules always matches the server build.
     *
     * @param name the gamerule name
     * @return the gamerule, or {@code null} if the server does not know it
     */
    public static @Nullable GameRule<?> resolveGameRule(@NotNull String name) {
        var index = gameRuleIndex;
        if (index == null) {
            var built = new HashMap<String, GameRule<?>>();
            for (var rule : Registry.GAME_RULE) {
                built.put(gameRuleName(rule).toLowerCase(Locale.ROOT), rule);
            }
            index = Map.copyOf(built);
            gameRuleIndex = index;
        }
        return index.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Applies the managed gamerules, skipping any the server does not recognise or
     * whose value does not parse for the rule's type.
     *
     * @param world   the live Bukkit world
     * @param mvWorld the managed row
     */
    private void applyGameRules(@NotNull World world, @NotNull MVWorld mvWorld) {
        mvWorld.getGameRules().forEach((name, value) -> {
            var rule = resolveGameRule(name);
            if (rule == null) {
                logger.warn("Unknown gamerule '{}' configured for world '{}' - skipping",
                        name, mvWorld.getIdentifier());
                return;
            }
            if (!applyGameRule(world, rule, value)) {
                logger.warn("Rejected gamerule value '{}={}' for world '{}'",
                        name, value, mvWorld.getIdentifier());
            }
        });
    }

    /**
     * Applies one gamerule, converting the stored string to the rule's value type.
     *
     * <p>Gamerules are either {@code Boolean} or {@code Integer}; anything else is
     * refused rather than guessed at.
     *
     * @param world the live Bukkit world
     * @param rule  the resolved gamerule
     * @param value the stored string value
     * @return {@code true} if the rule was applied
     */
    @SuppressWarnings("unchecked")
    private boolean applyGameRule(@NotNull World world, @NotNull GameRule<?> rule, @NotNull String value) {
        var type = rule.getType();
        if (type == Boolean.class) {
            return world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(value));
        }
        if (type == Integer.class) {
            try {
                return world.setGameRule((GameRule<Integer>) rule, Integer.valueOf(value.trim()));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Applies the per-world difficulty, if one is configured.
     *
     * @param world   the live Bukkit world
     * @param mvWorld the managed row
     */
    private void applyDifficulty(@NotNull World world, @NotNull MVWorld mvWorld) {
        var configured = mvWorld.getDifficulty();
        if (configured == null || configured.isBlank()) {
            return;
        }
        try {
            world.setDifficulty(Difficulty.valueOf(configured.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown difficulty '{}' for world '{}' - keeping server default",
                    configured, mvWorld.getIdentifier());
        }
    }

    /**
     * Pins the world's clock when a fixed time is configured.
     *
     * <p>Also disables {@code doDaylightCycle}, otherwise the time would drift away
     * from the pinned value between ticks of the holding task.
     *
     * @param world   the live Bukkit world
     * @param mvWorld the managed row
     */
    private void applyTime(@NotNull World world, @NotNull MVWorld mvWorld) {
        var fixed = mvWorld.getFixedTime();
        if (fixed == null) {
            return;
        }
        setBooleanRule(world, "doDaylightCycle", false);
        world.setTime(fixed);
    }

    /**
     * Pins the world's weather when weather is locked.
     *
     * <p>Also disables {@code doWeatherCycle} so the pinned state holds without a
     * repeating task.
     *
     * @param world   the live Bukkit world
     * @param mvWorld the managed row
     */
    private void applyWeather(@NotNull World world, @NotNull MVWorld mvWorld) {
        if (!mvWorld.isWeatherLocked()) {
            return;
        }
        setBooleanRule(world, "doWeatherCycle", false);
        var type = mvWorld.getWeatherType() == null
                ? "CLEAR"
                : mvWorld.getWeatherType().trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "RAIN" -> {
                world.setStorm(true);
                world.setThundering(false);
            }
            case "THUNDER" -> {
                world.setStorm(true);
                world.setThundering(true);
            }
            case "CLEAR" -> {
                world.setStorm(false);
                world.setThundering(false);
            }
            default -> logger.warn("Unknown weather type '{}' for world '{}' - leaving weather as-is",
                    type, mvWorld.getIdentifier());
        }
    }

    // ── World copy / deletion ───────────────────────────────────────────────────

    /**
     * Copies a world folder on disk under a new name.
     *
     * <p>Skips {@code uid.dat} and {@code session.lock}. Copying {@code uid.dat} would
     * give the clone the source world's UUID, which makes the server refuse to load
     * one of them; {@code session.lock} is held by the running server and is
     * regenerated on load anyway.
     *
     * <p>Runs entirely off the main thread. The caller is responsible for saving the
     * source world first, otherwise recently changed chunks may not be on disk yet.
     *
     * @param sourceName the world folder to copy
     * @param targetName the new folder name
     * @return a future completing with {@code true} if the copy succeeded
     */
    public @NotNull CompletableFuture<Boolean> copyWorldFiles(@NotNull String sourceName,
                                                              @NotNull String targetName) {
        return CompletableFuture.supplyAsync(() -> {
            var container = Bukkit.getWorldContainer().getAbsolutePath();
            var source = Path.of(container, sourceName);
            var target = Path.of(container, targetName);

            if (!Files.exists(source)) {
                logger.error("Cannot clone '{}': world folder does not exist", sourceName);
                return false;
            }
            if (Files.exists(target)) {
                logger.error("Cannot clone to '{}': target folder already exists", targetName);
                return false;
            }

            try (var walk = Files.walk(source)) {
                walk.forEach(path -> copyOneEntry(source, target, path));
                logger.info("Copied world folder '{}' to '{}'", sourceName, targetName);
                return true;
            } catch (IOException e) {
                logger.error("Failed to copy world folder '{}' to '{}'", sourceName, targetName, e);
                return false;
            }
        });
    }

    /**
     * Copies a single entry of a world folder, skipping the two files that must not
     * be duplicated. Extracted to keep {@link #copyWorldFiles} within the cognitive
     * complexity limit.
     *
     * @param source the source root
     * @param target the target root
     * @param path   the entry being copied
     */
    private void copyOneEntry(@NotNull Path source, @NotNull Path target, @NotNull Path path) {
        var name = path.getFileName().toString();
        if (SKIPPED_ON_COPY.contains(name)) {
            return;
        }
        try {
            Files.copy(path, target.resolve(source.relativize(path)),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.warn("Failed to copy '{}' while cloning: {}", path, e.getMessage());
        }
    }

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
