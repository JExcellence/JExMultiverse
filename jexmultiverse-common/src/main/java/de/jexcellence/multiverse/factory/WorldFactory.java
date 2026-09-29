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
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

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
     * {@code session.lock} is held by the running server and is recreated on load. The
     * remaining entries are per-dimension runtime state that the server writes in the
     * background and that a fresh world has to start without.
     */
    private static final Set<String> SKIPPED_ON_COPY = Set.of(
            "uid.dat", "session.lock", "chunk_tickets.dat", "raids.dat", "raids_end.dat",
            "wandering_trader.dat", "scheduled_events.dat", "paper-world.yml");

    /**
     * Top-level entries of a world folder that carry terrain or world data. Anything else
     * (backups, temp files, plugin leftovers) stays behind when a world is cloned.
     */
    private static final Set<String> COPIED_TOP_LEVEL = Set.of(
            "region", "entities", "poi", "data", "DIM-1", "DIM1", "level.dat");

    /** Paper 26.x stores the world UUID here instead of {@code uid.dat}. */
    private static final Path PAPER_METADATA = Path.of("data", "paper", "metadata.dat");

    private static final int DELETE_ATTEMPTS = 3;
    private static final long DELETE_RETRY_DELAY_MS = 1_000L;

    /** Gamerule names already reported as unknown, so each one is logged once per session. */
    private final Set<String> reportedUnknownRules = ConcurrentHashMap.newKeySet();

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
        return openBukkitWorld(new WorldSpec(name, environment, type,
                plotSizeOverride, roadWidthOverride, schematicName), false);
    }

    /**
     * What to open: the identifier plus everything that decides the generator.
     */
    private record WorldSpec(@NotNull String name,
                             World.@NotNull Environment environment,
                             @NotNull MVWorldType type,
                             @Nullable Integer plotSizeOverride,
                             @Nullable Integer roadWidthOverride,
                             @Nullable String schematicName) {}

    /**
     * Creates or opens a Bukkit world.
     *
     * @param spec          the world to open
     * @param allowExisting {@code true} when the folder is expected to exist already, as
     *                      for a persisted or freshly cloned world; {@code false} for a
     *                      brand-new world, where an existing legacy folder is refused
     * @return the world, or {@code null} on failure
     */
    private @Nullable World openBukkitWorld(@NotNull WorldSpec spec, boolean allowExisting) {
        var name = spec.name();
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            logger.warn("World '{}' is already loaded in Bukkit (env={}); use import/adopt, not create.",
                    name, existing.getEnvironment());
            return existing;
        }
        var folder = new File(Bukkit.getWorldContainer(), name);
        if (!allowExisting && folder.isDirectory() && new File(folder, "level.dat").isFile()) {
            logger.warn("World folder '{}' already exists on disk; use '/mv import {}' to adopt it "
                    + "instead of creating a new one.", name, name);
            return null;
        }
        var environment = spec.environment();
        var type = spec.type();
        var plotSizeOverride = spec.plotSizeOverride();
        var roadWidthOverride = spec.roadWidthOverride();
        var schematicName = spec.schematicName();
        try {
            var creator = new WorldCreator(name)
                    .environment(environment);

            var generator = getGeneratorForType(type, plotSizeOverride, roadWidthOverride, schematicName);
            if (generator != null) {
                creator.generator(generator);
            }

            var world = creator.createWorld();
            if (world == null) {
                // Bukkit swallowed the failure (usually reserved name / io error);
                // surface it with the coordinates we asked for so the operator has a
                // starting point instead of a bare 'null'.
                logger.error("Bukkit returned null when creating world '{}' (env={}, type={}). "
                        + "Common causes: reserved name, disk io failure, generator mismatch.",
                        name, environment, type);
                return null;
            }
            applySpawnChunkRadius(world, false);
            logger.info("Created Bukkit world '{}' (env={}, type={}, plot-override={}/{}, schematic={})",
                    name, environment, type, plotSizeOverride, roadWidthOverride, schematicName);
            return world;
        } catch (Exception e) {
            // Log the actual message + exception type so the operator sees WHY,
            // not just that it failed. Full stack still attached via the throwable arg.
            String msg = e.getMessage() != null ? e.getMessage() : "no message";
            logger.error("Failed to create Bukkit world '{}' ({}: {})",
                    name, e.getClass().getSimpleName(), msg, e);
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

        var world = openBukkitWorld(new WorldSpec(mvWorld.getIdentifier(), mvWorld.getEnvironment(),
                mvWorld.getType(), mvWorld.getPlotSizeOverride(), mvWorld.getRoadWidthOverride(),
                mvWorld.getSchematicName()), true);
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

        var success = unloadBukkitWorld(world, save);
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

    /**
     * Unloads a world, treating a server that refuses runtime unloads (Folia throws) as
     * a failed unload instead of letting the exception escape the scheduler task, which
     * would leave the caller's future pending forever.
     *
     * @param world the world to unload
     * @param save  whether to save chunks first
     * @return {@code true} if the server unloaded the world
     */
    private boolean unloadBukkitWorld(@NotNull World world, boolean save) {
        try {
            return Bukkit.unloadWorld(world, save);
        } catch (Exception e) {
            logger.warn("Server refused to unload world '{}': {}", world.getName(), e.getMessage());
            return false;
        }
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
        applySpawnChunkRadius(world, mvWorld.isKeepSpawnLoaded());
        applyGameRules(world, mvWorld);
        applyDifficulty(world, mvWorld);
        applyTime(world, mvWorld);
        applyWeather(world, mvWorld);
    }

    /**
     * Sets {@code spawnChunkRadius} where the server still has it.
     *
     * <p>26.x removed spawn chunks together with the rule, so there is nothing to keep
     * resident and the call is a quiet no-op there.
     *
     * @param world     the live Bukkit world
     * @param keepSpawn {@code true} for the vanilla radius of 2, {@code false} for 0
     */
    private void applySpawnChunkRadius(@NotNull World world, boolean keepSpawn) {
        setRule(world, "spawnChunkRadius", keepSpawn ? "2" : "0");
    }

    /**
     * Sets a gamerule by any of its names, resolved through {@link GameRuleResolver}.
     *
     * <p>Avoids the deprecated {@code GameRule} constants. A rule the server does not
     * know is reported once per session at debug level, since the server build decides
     * which rules exist.
     *
     * @param world the live Bukkit world
     * @param name  the gamerule name, legacy camelCase or 26.x snake_case
     * @param value the value as a string
     */
    private void setRule(@NotNull World world, @NotNull String name, @NotNull String value) {
        var resolved = GameRuleResolver.resolve(name);
        if (resolved == null) {
            reportUnknownRule(name, world.getName());
            return;
        }
        if (!applyGameRule(world, resolved, value)) {
            logger.debug("Gamerule '{}' rejected value '{}' in world '{}'", name, value, world.getName());
        }
    }

    /**
     * Logs an unknown gamerule once per name and session at debug level.
     *
     * @param name      the gamerule name
     * @param worldName the world it was requested for
     */
    private void reportUnknownRule(@NotNull String name, @NotNull String worldName) {
        if (reportedUnknownRules.add(name.toLowerCase(Locale.ROOT))) {
            logger.debug("Gamerule '{}' does not exist on this server build - skipping (first seen in '{}')",
                    name, worldName);
        }
    }

    /**
     * Returns a gamerule's name as the server reports it, for example {@code advance_time}
     * on 26.x. Stored names from older builds keep resolving through
     * {@link #resolveGameRule(String)}.
     *
     * @param rule the gamerule
     * @return the gamerule name
     */
    public static @NotNull String gameRuleName(@NotNull GameRule<?> rule) {
        return GameRuleResolver.name(rule);
    }

    /**
     * Resolves a gamerule by its name, case-insensitively, accepting the legacy camelCase
     * names that 26.x renamed.
     *
     * @param name the gamerule name
     * @return the gamerule, or {@code null} if the server does not know it
     */
    public static @Nullable GameRule<?> resolveGameRule(@NotNull String name) {
        var resolved = GameRuleResolver.resolve(name);
        return resolved == null ? null : resolved.rule();
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
            var resolved = GameRuleResolver.resolve(name);
            if (resolved == null) {
                reportUnknownRule(name, mvWorld.getIdentifier());
                return;
            }
            if (!applyGameRule(world, resolved, value)) {
                logger.warn("Rejected gamerule value '{}={}' for world '{}'",
                        name, value, mvWorld.getIdentifier());
            }
        });
    }

    /**
     * Applies one gamerule, converting the stored string to the rule's value type.
     *
     * <p>Gamerules are either {@code Boolean} or {@code Integer}; anything else is
     * refused rather than guessed at. A boolean stored under a legacy name whose 26.x
     * replacement means the opposite is flipped.
     *
     * @param world    the live Bukkit world
     * @param resolved the resolved gamerule
     * @param value    the stored string value
     * @return {@code true} if the rule was applied
     */
    @SuppressWarnings("unchecked")
    private boolean applyGameRule(@NotNull World world, @NotNull GameRuleResolver.Resolved resolved,
                                  @NotNull String value) {
        var rule = resolved.rule();
        var type = rule.getType();
        if (type == Boolean.class) {
            boolean parsed = Boolean.parseBoolean(value.trim());
            return world.setGameRule((GameRule<Boolean>) rule, parsed != resolved.inverted());
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
        setRule(world, "doDaylightCycle", "false");
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
        setRule(world, "doWeatherCycle", "false");
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
     * Resolves the folder a world lives in, or will live in once it is created.
     *
     * <p>A loaded world reports its own folder. For a world that is not loaded the folder
     * is derived from the primary world's layout: its parent is {@code <level>/dimensions/minecraft}
     * on 26.x, where {@link WorldCreator} places every world keyed {@code minecraft:<name>},
     * and the world container on older builds. Deriving both the copy target and the delete
     * target from here is what keeps them identical to the folder the server loads.
     *
     * <p>Call on the main / global region thread.
     *
     * @param name the world identifier
     * @return the absolute world folder
     */
    public @NotNull Path resolveWorldFolder(@NotNull String name) {
        var loaded = Bukkit.getWorld(name);
        if (loaded != null) {
            return loaded.getWorldPath().toAbsolutePath().normalize();
        }
        var worlds = Bukkit.getWorlds();
        Path parent = worlds.isEmpty()
                ? null
                : worlds.getFirst().getWorldPath().toAbsolutePath().normalize().getParent();
        if (parent == null) {
            parent = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        }
        return parent.resolve(name);
    }

    /**
     * Copies a world folder on disk into a new folder.
     *
     * <p>Only terrain and world data are copied: {@code region}, {@code entities},
     * {@code poi}, {@code data} and, on older builds, {@code level.dat} and the legacy
     * dimension folders. The world UUID ({@code uid.dat} or Paper's
     * {@code data/paper/metadata.dat}), {@code session.lock} and per-dimension runtime
     * files such as {@code chunk_tickets.dat} are skipped. A copy that fails halfway is
     * removed again, so the target never holds a half-written world.
     *
     * <p>Runs off the main thread. The source must not be loaded while it is copied,
     * because the server writes its data files in the background.
     *
     * @param source the world folder to copy
     * @param target the new world folder, which must not exist yet
     * @return a future completing with {@code true} if the copy succeeded
     */
    public @NotNull CompletableFuture<Boolean> copyWorldFiles(@NotNull Path source, @NotNull Path target) {
        return CompletableFuture.supplyAsync(() -> {
            if (!Files.isDirectory(source)) {
                logger.error("Cannot clone: world folder {} does not exist", source);
                return false;
            }
            if (Files.exists(target)) {
                logger.error("Cannot clone: target folder {} already exists", target);
                return false;
            }
            try {
                Files.walkFileTree(source, new WorldCopyVisitor(source, target));
                logger.info("Copied world folder {} to {}", source, target);
                return true;
            } catch (IOException e) {
                logger.error("Failed to copy world folder {} to {}: {}", source, target, e.getMessage());
                deleteTree(target);
                return false;
            }
        });
    }

    /**
     * Returns whether an entry of a world folder belongs in a clone.
     *
     * @param relative the entry's path relative to the world folder
     * @return {@code true} if the entry is copied
     */
    static boolean isCopiedOnClone(@NotNull Path relative) {
        if (relative.getNameCount() == 1 && !COPIED_TOP_LEVEL.contains(relative.toString())) {
            return false;
        }
        var name = relative.getFileName().toString();
        return !SKIPPED_ON_COPY.contains(name)
                && !name.endsWith(".tmp")
                && !name.endsWith("_old")
                && !relative.equals(PAPER_METADATA);
    }

    /**
     * Walks a world folder and copies the entries {@link #isCopiedOnClone(Path)} accepts.
     */
    private static final class WorldCopyVisitor extends SimpleFileVisitor<Path> {

        private final Path source;
        private final Path target;

        private WorldCopyVisitor(@NotNull Path source, @NotNull Path target) {
            this.source = source;
            this.target = target;
        }

        @Override
        public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir,
                                                          @NotNull BasicFileAttributes attrs) throws IOException {
            if (dir.equals(source)) {
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }
            var relative = source.relativize(dir);
            if (!isCopiedOnClone(relative)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            Files.createDirectories(target.resolve(relative));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public @NotNull FileVisitResult visitFile(@NotNull Path file,
                                                  @NotNull BasicFileAttributes attrs) throws IOException {
            var relative = source.relativize(file);
            if (isCopiedOnClone(relative)) {
                Files.copy(file, target.resolve(relative), StandardCopyOption.COPY_ATTRIBUTES);
            }
            return FileVisitResult.CONTINUE;
        }
    }

    /**
     * Deletes a world folder from disk.
     *
     * <p>The folder has to be named after the world and look like a world folder
     * ({@code region}, {@code data} or {@code level.dat} inside), otherwise nothing is
     * deleted. Windows can keep region files locked for a moment after an unload, so a
     * partial delete is retried a few times before it is reported.
     *
     * @param worldName the world identifier
     * @param folder    the resolved world folder, see {@link #resolveWorldFolder(String)}
     * @return a future completing with {@code true} once the folder is gone
     */
    public @NotNull CompletableFuture<Boolean> deleteWorldFiles(@NotNull String worldName, @NotNull Path folder) {
        if (!Files.exists(folder)) {
            logger.debug("World folder {} does not exist, nothing to delete", folder);
            return CompletableFuture.completedFuture(true);
        }
        var fileName = folder.getFileName();
        if (fileName == null || !fileName.toString().equalsIgnoreCase(worldName) || !looksLikeWorldFolder(folder)) {
            logger.warn("Refusing to delete {} for world '{}': not a world folder of that name", folder, worldName);
            return CompletableFuture.completedFuture(false);
        }
        return deleteAttempt(worldName, folder, 1);
    }

    private static boolean looksLikeWorldFolder(@NotNull Path folder) {
        return Files.isDirectory(folder.resolve("region"))
                || Files.isDirectory(folder.resolve("data"))
                || Files.isRegularFile(folder.resolve("level.dat"));
    }

    private @NotNull CompletableFuture<Boolean> deleteAttempt(@NotNull String worldName,
                                                              @NotNull Path folder,
                                                              int attempt) {
        Executor executor = attempt == 1
                ? ForkJoinPool.commonPool()
                : CompletableFuture.delayedExecutor(DELETE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
        return CompletableFuture.supplyAsync(() -> deleteTree(folder), executor).thenCompose(done -> {
            if (Boolean.TRUE.equals(done)) {
                logger.info("Deleted world folder {} of '{}'", folder, worldName);
                return CompletableFuture.completedFuture(true);
            }
            if (attempt >= DELETE_ATTEMPTS) {
                logger.warn("Could not fully delete world folder {} of '{}' after {} attempts",
                        folder, worldName, attempt);
                return CompletableFuture.completedFuture(false);
            }
            return deleteAttempt(worldName, folder, attempt + 1);
        });
    }

    /**
     * Deletes a directory tree, best effort.
     *
     * @param root the directory to delete
     * @return {@code true} if the directory no longer exists
     */
    private boolean deleteTree(@NotNull Path root) {
        if (!Files.exists(root)) {
            return true;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException | UncheckedIOException e) {
            logger.debug("Could not walk {} for deletion: {}", root, e.getMessage());
        }
        return !Files.exists(root);
    }

    private void deleteQuietly(@NotNull Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.debug("Could not delete {}: {}", path, e.getMessage());
        }
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
