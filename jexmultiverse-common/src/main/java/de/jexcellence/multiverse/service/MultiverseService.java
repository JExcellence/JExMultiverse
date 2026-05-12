package de.jexcellence.multiverse.service;

import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.server.ServerDetector;
import de.jexcellence.jexplatform.server.ServerType;
import de.jexcellence.multiverse.api.MVWorldSnapshot;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.api.MultiverseProvider;
import de.jexcellence.multiverse.api.PlotBounds;
import de.jexcellence.multiverse.api.PlotCoord;
import de.jexcellence.multiverse.api.PlotOwnership;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.database.repository.MVWorldRepository;
import de.jexcellence.multiverse.factory.BukkitYmlWriter;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.generator.GeneratorRegistry;
import de.jexcellence.multiverse.nbt.LevelDatBuilder;
import de.jexcellence.multiverse.spi.RuntimeWorldLoader;
import de.jexcellence.multiverse.spi.RuntimeWorldLoaderResolver;
import de.jexcellence.multiverse.util.CompanionWorldNameGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Unified multiverse service implementing both internal operations and the
 * public {@link MultiverseProvider} API. Delegates world lifecycle to
 * {@link WorldFactory} and persistence to {@link MVWorldRepository}.
 *
 * <p>This class is edition-aware: the injected {@link MultiverseEdition}
 * controls world limits and available generation types.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class MultiverseService implements MultiverseProvider {

    private final MultiverseEdition edition;
    private final MVWorldRepository repository;
    private final WorldFactory worldFactory;
    private final JExLogger logger;
    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;

    /** Late-bound: PlotService is wired after both services are constructed. */
    private @Nullable PlotService plotService;

    /**
     * Set once the runtime loader has logged a failure for the current
     * session. Subsequent per-world failures are demoted to {@code debug}
     * to keep the log clean — the operator already knows runtime load
     * doesn't work on this server build.
     */
    private volatile boolean runtimeLoadFailureLogged;

    public MultiverseService(@NotNull MultiverseEdition edition,
                             @NotNull MVWorldRepository repository,
                             @NotNull WorldFactory worldFactory,
                             @NotNull JExLogger logger,
                             @NotNull JavaPlugin plugin,
                             @NotNull PlatformScheduler scheduler) {
        this.edition = edition;
        this.repository = repository;
        this.worldFactory = worldFactory;
        this.logger = logger;
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    // ── Edition queries ─────────────────────────────────────────────────────────

    /**
     * Returns whether the current edition is premium.
     */
    public boolean isPremium() {
        return edition.isPremium();
    }

    /**
     * Returns the maximum number of worlds allowed, or {@code -1} for unlimited.
     */
    public int getMaxWorlds() {
        return edition.maxWorlds();
    }

    /**
     * Returns the world types available in the current edition.
     */
    public @NotNull List<MVWorldType> getAvailableWorldTypes() {
        return edition.availableTypes();
    }

    /**
     * Checks whether a world type is available in the current edition.
     */
    public boolean isWorldTypeAvailable(@NotNull MVWorldType type) {
        return edition.availableTypes().contains(type);
    }

    // ── World CRUD ──────────────────────────────────────────────────────────────

    /**
     * Creates a new managed world. The Bukkit world is created on the main thread,
     * then persisted asynchronously.
     *
     * @param name        the world name
     * @param environment the world environment
     * @param type        the world generation type
     * @return a future containing the created world, or empty on failure
     */
    public @NotNull CompletableFuture<Optional<MVWorld>> createWorld(@NotNull String name,
                                                                     World.@NotNull Environment environment,
                                                                     @NotNull MVWorldType type) {
        return createWorld(name, environment, type, null, null, null);
    }

    /**
     * Creates a managed world with per-world plot size and road width overrides.
     * Delegates to the full overload with {@code schematicName = null}.
     *
     * @param name              world identifier
     * @param environment       world environment
     * @param type              generation type
     * @param plotSizeOverride  per-world plot size (PLOT only), or {@code null}
     * @param roadWidthOverride per-world road width (PLOT only), or {@code null}
     * @return a future containing the created world, or empty on failure
     */
    public @NotNull CompletableFuture<Optional<MVWorld>> createWorld(@NotNull String name,
                                                                     World.@NotNull Environment environment,
                                                                     @NotNull MVWorldType type,
                                                                     @Nullable Integer plotSizeOverride,
                                                                     @Nullable Integer roadWidthOverride) {
        return createWorld(name, environment, type, plotSizeOverride, roadWidthOverride, null);
    }

    /**
     * Creates a managed world with per-world plot generation overrides + an
     * optional schematic. The override values are persisted on the entity so
     * subsequent server starts re-create the same generator.
     *
     * @param name              world identifier
     * @param environment       world environment
     * @param type              generation type
     * @param plotSizeOverride  per-world plot size (PLOT only), or {@code null}
     * @param roadWidthOverride per-world road width (PLOT only), or {@code null}
     * @param schematicName     per-world schematic file name without {@code .nbt}
     *                          extension (PLOT only), or {@code null}
     */
    public @NotNull CompletableFuture<Optional<MVWorld>> createWorld(@NotNull String name,
                                                                     World.@NotNull Environment environment,
                                                                     @NotNull MVWorldType type,
                                                                     @Nullable Integer plotSizeOverride,
                                                                     @Nullable Integer roadWidthOverride,
                                                                     @Nullable String schematicName) {
        if (isAtWorldLimit()) {
            logger.warn("World limit reached ({}/{}), cannot create '{}'",
                    getWorldCount(), getMaxWorlds(), name);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        if (!isWorldTypeAvailable(type)) {
            logger.warn("World type '{}' not available in current edition", type);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        if (worldFactory.getCachedWorld(name).isPresent()) {
            logger.warn("World '{}' already exists", name);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Overrides only apply to PLOT worlds; clear them otherwise so the
        // entity faithfully represents how the world was generated.
        var effectivePlotSize = type == MVWorldType.PLOT ? plotSizeOverride : null;
        var effectiveRoadWidth = type == MVWorldType.PLOT ? roadWidthOverride : null;
        var effectiveSchematic = type == MVWorldType.PLOT ? schematicName : null;

        var future = new CompletableFuture<Optional<MVWorld>>();
        // PlatformScheduler.runSync hits GlobalRegionScheduler on Folia
        // (where Bukkit.createWorld requires the global region) and the
        // main thread on Paper. Bukkit.getScheduler() throws UOE on
        // Folia outright.
        scheduler.runSync(() -> {
            var bukkitWorld = worldFactory.createBukkitWorld(name, environment, type,
                    effectivePlotSize, effectiveRoadWidth, effectiveSchematic);
            if (bukkitWorld == null) {
                future.complete(Optional.empty());
                return;
            }

            var spawn = worldFactory.getDefaultSpawnForType(bukkitWorld, type);
            var mvWorld = MVWorld.builder()
                    .identifier(name)
                    .type(type)
                    .environment(environment)
                    .spawnLocation(spawn)
                    .globalizedSpawn(false)
                    .pvpEnabled(true)
                    .plotSizeOverride(effectivePlotSize)
                    .roadWidthOverride(effectiveRoadWidth)
                    .schematicName(effectiveSchematic)
                    .build();

            repository.saveWorld(mvWorld).thenAccept(saved -> {
                worldFactory.cacheWorld(saved);
                logger.info("Created and persisted world '{}'", name);
                future.complete(Optional.of(saved));
            }).exceptionally(ex -> {
                logger.error("Failed to persist world '{}'", name, ex);
                future.complete(Optional.empty());
                return null;
            });
        });

        return future;
    }

    /**
     * Deletes a managed world: unloads from Bukkit, removes from database, and
     * deletes files from disk.
     *
     * @param identifier the world name
     * @return a future containing {@code true} if deletion succeeded
     */
    public @NotNull CompletableFuture<Boolean> deleteWorld(@NotNull String identifier) {
        // ORDER MATTERS: DB row is removed BEFORE the Bukkit world is unloaded.
        //   1. Cascade-delete the plot rows in this world (otherwise they're
        //      orphaned + the protection listener gets confused on restart).
        //   2. Delete the MVWorld DB row WHILE the Bukkit world is still
        //      loaded — the LocationConverter on spawn_location can resolve
        //      the world UUID, so Hibernate doesn't surface
        //      "LogicalConnectionManagedImpl is closed" trying to materialise
        //      a Location with a null World.
        //   3. THEN unload the Bukkit world on the main thread.
        //   4. THEN walk the world folder and delete the files.
        var future = new CompletableFuture<Boolean>();

        cascadeDeletePlots(identifier)
                .thenCompose(v -> repository.deleteByIdentifier(identifier))
                .thenAccept(dbDeleted -> scheduler.runSync(() -> {
                    if (!dbDeleted) {
                        future.complete(false);
                        return;
                    }
                    var unloaded = worldFactory.unloadWorld(identifier, false);
                    if (!unloaded) {
                        // DB row gone but world still loaded — log loudly so the
                        // admin knows to restart to fully reclaim the slot.
                        logger.warn("Deleted DB row for '{}' but failed to unload Bukkit world. " +
                                "Restart the server to fully release the world.", identifier);
                        worldFactory.invalidateCache(identifier);
                        future.complete(true);
                        return;
                    }
                    worldFactory.deleteWorldFiles(identifier).thenAccept(filesDeleted -> {
                        worldFactory.invalidateCache(identifier);
                        logger.info("Deleted world '{}' (db + bukkit + files)", identifier);
                        future.complete(filesDeleted);
                    });
                }))
                .exceptionally(ex -> {
                    logger.error("Failed to delete world '{}'", identifier, ex);
                    future.complete(false);
                    return null;
                });

        return future;
    }

    /**
     * Hook for cascade-deleting plot rows when a world is deleted. Wired up
     * post-construction by {@link #attachPlotService(PlotService)}; called
     * before the MVWorld row itself is deleted.
     */
    private @NotNull CompletableFuture<Void> cascadeDeletePlots(@NotNull String worldIdentifier) {
        if (plotService == null) return CompletableFuture.completedFuture(null);
        return plotService.deletePlotsInWorld(worldIdentifier);
    }

    /**
     * Retrieves a world from the cache or database.
     *
     * @param identifier the world name
     * @return a future containing the world, or empty if not found
     */
    public @NotNull CompletableFuture<Optional<MVWorld>> getWorldEntity(@NotNull String identifier) {
        var cached = worldFactory.getCachedWorld(identifier);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(cached);
        }
        return repository.findByIdentifierAsync(identifier).thenApply(opt -> {
            opt.ifPresent(worldFactory::cacheWorld);
            return opt;
        });
    }

    /**
     * Returns all managed worlds from the cache.
     */
    public @NotNull Collection<MVWorld> getAllWorldEntities() {
        return worldFactory.getAllCachedWorlds();
    }

    /**
     * Updates a world entity in the database and refreshes the cache.
     *
     * @param changes the world to update
     * @return a future containing the updated world
     */
    public @NotNull CompletableFuture<MVWorld> updateWorld(@NotNull MVWorld changes) {
        // Re-fetch a fresh entity by identifier and copy persisted fields onto
        // it, so we never hand Hibernate a stale/detached instance whose
        // session was closed (which can manifest as
        // "LogicalConnectionManagedImpl is closed" during transaction work).
        return repository.findByIdentifierAsync(changes.getIdentifier())
                .thenCompose(opt -> {
                    var target = opt.orElse(changes);
                    // spawnLocation is NOT NULL in the schema. Only overwrite
                    // when the editor actually carried a fresh value, otherwise
                    // keep whatever Hibernate just loaded — preserves DB state
                    // and prevents not-null constraint violations.
                    if (changes.getSpawnLocation() != null) {
                        target.setSpawnLocation(changes.getSpawnLocation());
                    }
                    target.setGlobalizedSpawn(changes.isGlobalizedSpawn());
                    target.setPvpEnabled(changes.isPvpEnabled());
                    target.setEnterPermission(changes.getEnterPermission());
                    return repository.saveWorld(target);
                })
                .thenApply(saved -> {
                    worldFactory.cacheWorld(saved);
                    logger.debug("Updated world '{}'", saved.getIdentifier());
                    return saved;
                });
    }

    // ── Spawn management ────────────────────────────────────────────────────────

    /**
     * Sets the spawn location for a specific world.
     *
     * @param identifier the world name
     * @param location   the new spawn location
     * @return a future containing {@code true} if the spawn was set
     */
    public @NotNull CompletableFuture<Boolean> setSpawn(@NotNull String identifier,
                                                         @NotNull Location location) {
        return getWorldEntity(identifier).thenCompose(opt -> {
            if (opt.isEmpty()) return CompletableFuture.completedFuture(false);
            var world = opt.get();
            world.setSpawnLocation(location);
            return repository.saveWorld(world).thenApply(saved -> {
                worldFactory.cacheWorld(saved);
                return true;
            });
        });
    }

    /**
     * Designates a world as the global spawn. Clears the global-spawn flag from
     * all other worlds.
     *
     * @param identifier the world name
     * @return a future containing {@code true} if the global spawn was set
     */
    public @NotNull CompletableFuture<Boolean> setGlobalSpawn(@NotNull String identifier) {
        return getWorldEntity(identifier).thenCompose(opt -> {
            if (opt.isEmpty()) return CompletableFuture.completedFuture(false);
            var world = opt.get();
            return repository.clearGlobalSpawnExcept(identifier).thenCompose(v -> {
                world.setGlobalizedSpawn(true);
                return repository.saveWorld(world).thenApply(saved -> {
                    worldFactory.cacheWorld(saved);
                    worldFactory.refreshCache();
                    return true;
                });
            });
        });
    }

    /**
     * Finds the world designated as the global spawn.
     *
     * @return a future containing the global spawn world, or empty
     */
    public @NotNull CompletableFuture<Optional<MVWorld>> getGlobalSpawnWorldEntity() {
        return repository.findByGlobalSpawnAsync();
    }

    /**
     * Resolves the spawn location for a player. Priority:
     * <ol>
     *   <li>Global spawn world's spawn location</li>
     *   <li>Current world's multiverse spawn</li>
     *   <li>Default world spawn</li>
     * </ol>
     *
     * @param player the player
     * @return a future containing the resolved spawn location, or {@code null}
     */
    public @NotNull CompletableFuture<@Nullable Location> getSpawnLocation(@NotNull Player player) {
        return getGlobalSpawnWorldEntity().thenApply(opt -> {
            // Priority 1 — global spawn from any world flagged isGlobalizedSpawn.
            if (opt.isPresent()) {
                var loc = liveSpawnLocation(opt.get());
                if (loc != null) return loc;
            }

            // Priority 2 — current world's JExMultiverse-set spawn.
            var cached = worldFactory.getCachedWorld(player.getWorld().getName());
            if (cached.isPresent()) {
                var loc = liveSpawnLocation(cached.get());
                if (loc != null) return loc;
            }

            // Final fallback — Bukkit default world spawn (only when no MV
            // configuration applies). Returns null if even that's missing so
            // the caller can show a clear "no spawn configured" message.
            var bukkitWorlds = Bukkit.getWorlds();
            return bukkitWorlds.isEmpty() ? null : bukkitWorlds.getFirst().getSpawnLocation();
        });
    }

    /**
     * Resolves the live, teleportable spawn for an MVWorld:
     * the stored Location must be non-null AND its world must currently be
     * loaded in Bukkit (rebinding by name if the deserialized world reference
     * is stale). Returns {@code null} if either constraint fails so callers
     * can fall through to the next priority tier.
     */
    private @Nullable Location liveSpawnLocation(@NotNull MVWorld mv) {
        var stored = mv.getSpawnLocation();
        if (stored == null) return null;
        var world = stored.getWorld() != null ? stored.getWorld() : Bukkit.getWorld(mv.getIdentifier());
        if (world == null) return null;
        var loc = stored.clone();
        loc.setWorld(world);
        return loc;
    }

    // ── Query helpers ───────────────────────────────────────────────────────────

    /**
     * Checks whether a world with the given name exists in the cache or database.
     */
    public @NotNull CompletableFuture<Boolean> worldExists(@NotNull String identifier) {
        if (worldFactory.getCachedWorld(identifier).isPresent()) {
            return CompletableFuture.completedFuture(true);
        }
        return repository.findByIdentifierAsync(identifier).thenApply(Optional::isPresent);
    }

    /**
     * Returns the server's default world (the first world in Bukkit's world list).
     * This world serves as the parent for NORMAL companion worlds on Folia.
     *
     * @return the default world, or {@code null} if no worlds are loaded
     */
    private @Nullable World getDefaultWorld() {
        var worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            logger.error("Cannot resolve default world: server has no loaded worlds");
            return null;
        }
        return worlds.get(0);
    }

    /**
     * Checks whether the world limit has been reached for the current edition.
     */
    public boolean isAtWorldLimit() {
        int max = edition.maxWorlds();
        return max >= 0 && getWorldCount() >= max;
    }

    /**
     * Returns the number of managed worlds currently cached.
     */
    public int getWorldCount() {
        return worldFactory.getAllCachedWorlds().size();
    }

    // ── MultiverseProvider API ──────────────────────────────────────────────────

    @Override
    public @NotNull CompletableFuture<Optional<MVWorldSnapshot>> getWorld(@NotNull String identifier) {
        return getWorldEntity(identifier).thenApply(opt -> opt.map(MVWorld::toSnapshot));
    }

    @Override
    public @NotNull CompletableFuture<Optional<MVWorldSnapshot>> getGlobalSpawnWorld() {
        return getGlobalSpawnWorldEntity().thenApply(opt -> opt.map(MVWorld::toSnapshot));
    }

    @Override
    public @NotNull CompletableFuture<List<MVWorldSnapshot>> getAllWorlds() {
        return repository.findAllAsync().thenApply(worlds ->
                worlds.stream().map(MVWorld::toSnapshot).toList());
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasMultiverseSpawn(@NotNull String worldName) {
        return getWorldEntity(worldName).thenApply(opt ->
                opt.isPresent() && opt.get().getSpawnLocation() != null);
    }

    // ── Plot grid API ───────────────────────────────────────────────────────────

    @Override
    public @NotNull Optional<PlotCoord> plotAt(@NotNull Location location) {
        var w = location.getWorld();
        if (w == null) return Optional.empty();
        var name = w.getName();
        var mvOpt = worldFactory.getCachedWorld(name);
        if (mvOpt.isEmpty() || mvOpt.get().getType() != MVWorldType.PLOT) return Optional.empty();

        var mv = mvOpt.get();
        int plotSize = worldFactory.effectivePlotSize(mv);
        int roadWidth = worldFactory.effectiveRoadWidth(mv);
        int interval = plotSize + roadWidth;
        int modX = Math.floorMod(location.getBlockX(), interval);
        int modZ = Math.floorMod(location.getBlockZ(), interval);
        if (modX >= plotSize || modZ >= plotSize) return Optional.empty();

        int gridX = Math.floorDiv(location.getBlockX(), interval);
        int gridZ = Math.floorDiv(location.getBlockZ(), interval);
        return Optional.of(new PlotCoord(name, gridX, gridZ));
    }

    @Override
    public @NotNull Optional<PlotBounds> plotBounds(@NotNull String worldIdentifier, int gridX, int gridZ) {
        var mvOpt = worldFactory.getCachedWorld(worldIdentifier);
        if (mvOpt.isEmpty() || mvOpt.get().getType() != MVWorldType.PLOT) return Optional.empty();

        var mv = mvOpt.get();
        int plotSize = worldFactory.effectivePlotSize(mv);
        int roadWidth = worldFactory.effectiveRoadWidth(mv);
        int interval = plotSize + roadWidth;
        int minX = gridX * interval;
        int minZ = gridZ * interval;
        int maxX = minX + plotSize - 1;
        int maxZ = minZ + plotSize - 1;
        return Optional.of(new PlotBounds(worldIdentifier, gridX, gridZ, minX, minZ, maxX, maxZ,
                worldFactory.plotConfig().plotHeight()));
    }

    /**
     * Wires the plot service after both services are constructed (breaking the
     * construction-time cycle since PlotService also takes MultiverseService).
     */
    public void attachPlotService(@NotNull PlotService plotService) {
        this.plotService = plotService;
    }

    /**
     * Detects and adopts companion worlds that were created at runtime but are not
     * yet tracked by JExMultiverse. This runs on server startup after loading all
     * persisted worlds from the database.
     * <p>
     * Companion worlds follow the naming pattern {@code <parent>_<custom_name>}.
     * For example, if the default world is "world", a companion world might be
     * "world_oneblock_overworld".
     * <p>
     * This method:
     * <ol>
     *   <li>Iterates through all loaded Bukkit worlds</li>
     *   <li>Checks if each world matches the companion naming pattern</li>
     *   <li>Verifies the parent world exists</li>
     *   <li>Adopts the companion world if it's not already cached</li>
     * </ol>
     *
     * @return a future that completes when all companion worlds are adopted
     */
    public @NotNull CompletableFuture<Void> adoptCompanionWorldsOnStartup() {
        var serverType = ServerDetector.detect();
        boolean isFolia = serverType instanceof ServerType.Folia;
        
        if (!isFolia) {
            // Companion world adoption only applies to Folia
            logger.debug("[startup] Skipping companion world adoption (not running on Folia)");
            return CompletableFuture.completedFuture(null);
        }
        
        logger.info("[startup] Scanning for companion worlds to adopt...");
        
        var adoptionFutures = new java.util.ArrayList<CompletableFuture<Void>>();
        var bukkitWorlds = Bukkit.getWorlds();
        
        for (var world : bukkitWorlds) {
            var worldName = world.getName();
            
            // Skip if already cached
            if (worldFactory.getCachedWorld(worldName).isPresent()) {
                continue;
            }
            
            // Filter out Bukkit auto-generated nether/end companions. Bukkit auto-pairs
            // every overworld with "<name>_nether" and "<name>_the_end" worlds — those
            // are companions of the OVERWORLD, not user-created JExMultiverse worlds.
            // Adopting them would corrupt the world limit count and treat dimensional
            // siblings as standalone managed worlds.
            if (worldName.endsWith("_nether") || worldName.endsWith("_the_end") || worldName.endsWith("_end")) {
                continue;
            }

            // Check if this world matches the companion naming pattern: <parent>_<custom_name>
            // The pattern requires at least one underscore
            var underscoreIndex = worldName.indexOf('_');
            if (underscoreIndex <= 0 || underscoreIndex >= worldName.length() - 1) {
                // No underscore, or underscore at start/end - not a companion world
                continue;
            }

            // Extract potential parent name (everything before the first underscore)
            var potentialParentName = worldName.substring(0, underscoreIndex);

            // Verify the parent world exists
            var parentWorld = Bukkit.getWorld(potentialParentName);
            if (parentWorld == null) {
                // Parent doesn't exist - not a valid companion world
                continue;
            }

            // Defensive guard: the parent world should NOT itself be a Folia
            // companion (i.e. end with _nether/_the_end). If we somehow land
            // here it means the world is e.g. "world_nether_custom" — the
            // "parent" lookup would resolve "world" which is wrong. Skip.
            final String parent = parentWorld.getName();
            if (parent.endsWith("_nether") || parent.endsWith("_the_end") || parent.endsWith("_end")) {
                continue;
            }
            
            // This looks like a companion world - adopt it
            logger.info("[startup] Detected companion world '{}' (parent: '{}') - adopting into JExMultiverse",
                    worldName, potentialParentName);
            
            // Adopt with DEFAULT type as the default - the actual type will be
            // loaded from the database if a row already exists
            var adoptionFuture = adoptLoadedWorld(world, de.jexcellence.multiverse.api.MVWorldType.DEFAULT)
                    .thenAccept(snapshot -> {
                        if (snapshot.isPresent()) {
                            logger.info("[startup] Successfully adopted companion world '{}'", worldName);
                        } else {
                            logger.warn("[startup] Failed to adopt companion world '{}'", worldName);
                        }
                    })
                    .exceptionally(ex -> {
                        logger.error("[startup] Error adopting companion world '{}': {}",
                                worldName, rootMessage(ex));
                        return null;
                    })
                    .thenApply(v -> (Void) null); // Convert to CompletableFuture<Void>

            adoptionFutures.add(adoptionFuture);
        }
        
        if (adoptionFutures.isEmpty()) {
            logger.info("[startup] No companion worlds found to adopt");
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.allOf(adoptionFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> logger.info("[startup] Companion world adoption complete"));
    }

    @Override
    public @NotNull Optional<PlotOwnership> plotOwnership(@NotNull Location location) {
        if (plotService == null) return Optional.empty();
        return plotService.getPlotAt(location).map(p -> new PlotOwnership(
                p.getWorldName(), p.getGridX(), p.getGridZ(),
                p.getOwnerUuid(), p.getOwnerName(),
                p.getMergedGroupId(), p.getClaimedAt()));
    }

    @Override
    public boolean canBuild(@NotNull Player player, @NotNull Location location) {
        if (plotService == null) return true; // plots not initialized — fail open
        var plot = plotService.getPlotAt(location).orElse(null);
        if (plot == null) return true; // unclaimed / road / non-plot world
        return plotService.canBuild(player, plot);
    }

    @Override
    public @NotNull Optional<Boolean> getPlotFlag(@NotNull Location location, @NotNull String flagKey) {
        if (plotService == null) return Optional.empty();
        var plot = plotService.getPlotAt(location).orElse(null);
        if (plot == null) return Optional.empty();
        return PlotFlag.byKey(flagKey).map(flag -> plotService.getFlag(plot, flag));
    }

    @Override
    public @NotNull CompletableFuture<Optional<MVWorldSnapshot>> ensureWorld(@NotNull String name,
                                                                              World.@NotNull Environment environment,
                                                                              @NotNull MVWorldType type) {
        // Already managed → return the existing snapshot. We check both
        // the in-memory cache and Bukkit's world list so a world that
        // exists on disk but isn't yet registered with us (e.g. a
        // pre-Multiverse world the operator created via the server
        // config) still short-circuits the create path.
        var cached = worldFactory.getCachedWorld(name);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(Optional.of(cached.get().toSnapshot()));
        }

        // Edition + limit checks BEFORE any disk/Bukkit work. These previously
        // only fired inside createWorld(...) which meant the Folia companion
        // path and ensureViaBukkitYml bypassed them entirely. We re-evaluate
        // here so every code path enforces the same rules.
        // Existing-but-not-cached worlds (Bukkit.getWorld != null) are still
        // adopted further down — they were created via some other path and
        // adopting them is not a "new" creation.
        if (Bukkit.getWorld(name) == null && !isWorldTypeAvailable(type)) {
            logger.warn("[worlds] world type '{}' not available in current edition — rejecting '{}'", type, name);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (Bukkit.getWorld(name) == null && isAtWorldLimit()) {
            logger.warn("[worlds] world limit reached ({}/{}) — rejecting '{}'",
                    getWorldCount(), getMaxWorlds(), name);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Detect platform for routing decisions
        var serverType = ServerDetector.detect();
        boolean isFolia = serverType instanceof ServerType.Folia;

        // On Folia, ALL environments (NORMAL, NETHER, THE_END) use companion world creation.
        // Check the companion name in the cache before proceeding.
        if (isFolia) {
            final String companionSuffix;
            if (environment == World.Environment.NORMAL) {
                // For NORMAL worlds on Folia, generate companion name using the default world as parent
                var defaultWorld = getDefaultWorld();
                if (defaultWorld != null) {
                    final String companionName = CompanionWorldNameGenerator.generateCompanionName(
                            defaultWorld.getName(), name);
                    
                    // Check cache for companion name
                    var companionCached = worldFactory.getCachedWorld(companionName);
                    if (companionCached.isPresent()) {
                        logger.info("[worlds] Companion world '{}' already exists in cache, returning existing world", companionName);
                        return CompletableFuture.completedFuture(Optional.of(companionCached.get().toSnapshot()));
                    }
                    
                    // Check if companion world is loaded in Bukkit but not cached
                    var companionWorld = Bukkit.getWorld(companionName);
                    if (companionWorld != null) {
                        logger.info("[worlds] Companion world '{}' already exists in Bukkit, adopting into JExMultiverse", companionName);
                        return adoptLoadedWorld(companionWorld, type);
                    }
                }
            } else if (environment == World.Environment.NETHER || environment == World.Environment.THE_END) {
                // Existing NETHER/END companion logic
                companionSuffix = environment == World.Environment.NETHER ? "_nether" : "_the_end";
                final String overworldName = name.endsWith(companionSuffix)
                        ? name.substring(0, name.length() - companionSuffix.length())
                        : name;
                final String companionName = overworldName + companionSuffix;
                if (!companionName.equals(name)) {
                    var companionCached = worldFactory.getCachedWorld(companionName);
                    if (companionCached.isPresent()) {
                        return CompletableFuture.completedFuture(Optional.of(companionCached.get().toSnapshot()));
                    }
                }
            }
        }

        // If the world is already loaded in Bukkit but has no MVWorld row yet
        // (e.g. it was declared in bukkit.yml on a previous run via the
        // pending-restart path but the DB row was never written), adopt it now
        // so the row is persisted and the cache is populated. This covers both
        // Folia and Paper restarts where the world loaded from bukkit.yml but
        // ensureViaBukkitYml never reached adoptLoadedWorld.
        var liveWorld = Bukkit.getWorld(name);
        if (liveWorld != null) {
            logger.info("[worlds] '{}' is loaded in Bukkit but not in cache -- adopting into JExMultiverse", name);
            return adoptLoadedWorld(liveWorld, type);
        }

        // Platform-based routing:
        // - On Folia: NORMAL environments use companion creation, NETHER/END use bukkit.yml path
        // - On Paper/Spigot: NORMAL worlds use primary world creation, NETHER/END use existing logic
        if (isFolia) {
            if (environment == World.Environment.NORMAL) {
                // NORMAL worlds on Folia: create as companion to default world
                return createNormalCompanion(name, type);
            } else {
                // NETHER/END on Folia: use bukkit.yml path (existing logic)
                // Folia blocks Bukkit.createWorld at the API surface — there is
                // no runtime path to create a fresh world on Folia. The
                // maintainer-blessed alternative (Folia issue #396) is to
                // declare the world in bukkit.yml so it loads at next server
                // startup via the same code path that brings up the default
                // world. Route NETHER/END environments through the bukkit.yml path.
                return ensureViaBukkitYml(name, environment, type);
            }
        } else {
            // Paper/Spigot: NORMAL worlds use primary world creation
            // Not in our cache yet — delegate to createWorld, which handles
            // the WorldCreator call, persistence, and Folia-safe scheduling.
            // On limit / edition restriction it returns empty; on success a
            // MVWorld which we lift into a snapshot for the public API.
            return createWorld(name, environment, type).thenApply(opt ->
                    opt.map(MVWorld::toSnapshot));
        }
    }

    /**
     * Folia path: writes a {@code worlds.<name>} entry to
     * {@code bukkit.yml} (the platform-sanctioned way to register a new
     * world on Folia per PaperMC/Folia#396) and returns a pending-load
     * snapshot. The DB row is NOT persisted yet because the
     * {@link MVWorld#getSpawnLocation()} column is non-nullable and the
     * world doesn't exist on disk — we can't synthesize a valid
     * {@link org.bukkit.Location} (it'd need a {@link World} reference)
     * until after the operator restarts. The runtime path back through
     * {@link #ensureWorld} on the second start will see the world
     * cached and short-circuit there.
     *
     * <p>On the next server start, {@link #ensureWorld} will detect the world
     * is loaded in Bukkit (via bukkit.yml) but not in the cache, and will call
     * {@link #adoptLoadedWorld} to persist the DB row automatically.
     *
     * @see #ensureWorld
     */
    private @NotNull CompletableFuture<Optional<MVWorldSnapshot>> ensureViaBukkitYml(
            @NotNull String name,
            World.@NotNull Environment environment,
            @NotNull MVWorldType type) {
        // On Folia, NETHER and THE_END worlds are NOT independent top-level worlds.
        // When Folia loads an OVERWORLD from bukkit.yml it auto-creates companion
        // worlds named "<overworld>_nether" and "<overworld>_the_end". Declaring
        // oneblock_nether as a separate bukkit.yml entry causes a UUID collision
        // with the auto-generated oneblock_overworld_nether companion.
        //
        // Fix: for NETHER/THE_END, derive the overworld name, ensure the overworld
        // is declared in bukkit.yml, and resolve the Folia companion world name.
        if (environment == World.Environment.NETHER || environment == World.Environment.THE_END) {
            final String companionSuffix = environment == World.Environment.NETHER ? "_nether" : "_the_end";

            // Derive the base name by stripping the environment suffix if present.
            // Common patterns:
            //   oneblock_nether → base: oneblock
            //   oneblock_end → base: oneblock (note: _end, not _the_end)
            //   oneblock_the_end → base: oneblock
            final String baseName;
            if (name.endsWith("_nether")) {
                baseName = name.substring(0, name.length() - "_nether".length());
            } else if (name.endsWith("_the_end")) {
                baseName = name.substring(0, name.length() - "_the_end".length());
            } else if (name.endsWith("_end")) {
                baseName = name.substring(0, name.length() - "_end".length());
            } else {
                // Name doesn't follow the convention — use as-is
                baseName = name;
            }

            // The overworld name is typically <base>_overworld. If the base already
            // ends with _overworld, use it as-is; otherwise append _overworld.
            final String overworldName = baseName.endsWith("_overworld")
                    ? baseName
                    : baseName + "_overworld";

            // The Folia companion world name: <overworld>_nether or <overworld>_the_end
            final String companionName = overworldName + companionSuffix;

            // If the companion is already loaded, adopt it directly
            final World companionWorld = Bukkit.getWorld(companionName);
            if (companionWorld != null) {
                logger.info("[worlds] '{}' resolved to Folia companion '{}' — adopting", name, companionName);
                return adoptLoadedWorld(companionWorld, type);
            }

            // Companion not loaded yet — ensure the overworld is declared so
            // Folia creates the companions on next restart. Return a pending
            // snapshot using the companion name so callers know what to expect.
            logger.info("[worlds] '{}' is a Folia companion — ensuring overworld '{}' is declared in bukkit.yml", name, overworldName);
            return ensureViaBukkitYml(overworldName, World.Environment.NORMAL, type)
                    .thenApply(opt -> {
                        if (opt.isEmpty()) return Optional.<MVWorldSnapshot>empty();
                        return Optional.of(new MVWorldSnapshot(
                                0L,
                                companionName,
                                type,
                                environment.name(),
                                0.0, 0.0, 0.0,
                                0.0f, 0.0f,
                                false,
                                true,
                                null
                        ));
                    });
        }

        try {
            // Step 1: synthesise the on-disk world skeleton (<root>/<name>/level.dat
            // + session.lock) so the server's startup scan discovers and
            // loads it like any vanilla world directory. This is what
            // actually triggers world load on Folia (and Paper/Spigot);
            // bukkit.yml below only provides the generator config.
            //
            // If the world directory already exists (e.g. from a previous
            // pending-restart run), do NOT re-write level.dat. Re-writing it
            // causes Bukkit to assign a new UUID on next load, which collides
            // with the uid.dat written during the previous load and produces
            // "duplicate world" errors. Instead, just delete the stale uid.dat
            // so the next startup loads cleanly.
            final java.io.File worldContainer;
            try {
                worldContainer = org.bukkit.Bukkit.getWorldContainer();
            } catch (final Throwable ignored2) {
                // Bukkit not yet initialised (e.g. tests) -- fall back to CWD
                throw new java.io.IOException("Bukkit.getWorldContainer() unavailable");
            }
            final java.io.File worldDir = new java.io.File(worldContainer, name);
            if (worldDir.exists() && new java.io.File(worldDir, "level.dat").exists()) {
                // World directory already on disk -- only clean up uid.dat
                final java.io.File uidDat = new java.io.File(worldDir, "uid.dat");
                if (uidDat.exists()) {
                    uidDat.delete(); // best-effort; safe to ignore failure
                }
                logger.debug("[worlds] '{}' world directory already exists -- skipping skeleton write", name);
            } else {
                LevelDatBuilder.writeSkeleton(name, environment);
            }

            // Step 2: declare the generator in bukkit.yml so the server
            // wires our ChunkGenerator override (JExMultiverse:void) when
            // it loads the freshly-discovered world directory.
            final String generator = GeneratorRegistry.voidGeneratorRef();
            final var writeResult = BukkitYmlWriter.declare(name, generator, logger);

            // Step 3: try the runtime loader (Folia-NMS or inline-create
            // on Paper). If it succeeds, the world is live NOW and the
            // caller can immediately teleport into it; if not, we fall
            // back to the pending-restart path below.
            final Optional<RuntimeWorldLoader> backend = RuntimeWorldLoaderResolver.resolve(logger);
            if (backend.isPresent()) {
                try {
                    final World loaded = backend.get().loadWorld(name, environment).join();
                    logger.info("[worlds] '{}' loaded at runtime via {} backend",
                            name, backend.get().backendId());
                    return adoptLoadedWorld(loaded, type);
                } catch (final Throwable ex) {
                    // First failure of the session: warn so operators see it.
                    // Subsequent failures: debug. On Folia 1.21.x runtime load
                    // is fundamentally unavailable (no API), so spamming warn
                    // per world isn't useful.
                    if (!runtimeLoadFailureLogged) {
                        runtimeLoadFailureLogged = true;
                        logger.warn("[worlds] runtime load unavailable via {} backend: {} -- all new worlds will use pending-restart path",
                                backend.get().backendId(), rootMessage(ex));
                    } else {
                        logger.debug("[worlds] runtime load failed for '{}' (already-known limitation): {}",
                                name, rootMessage(ex));
                    }
                }
            }

            if (writeResult.alreadyDeclared()) {
                logger.info("[worlds] '{}' skeleton written; already declared in bukkit.yml -- restart the server to load it",
                        name);
            } else if (writeResult.added()) {
                logger.warn("[worlds] '{}' skeleton + bukkit.yml entry written -- restart the server to finish creating the world",
                        name);
            }

            final var snapshot = new MVWorldSnapshot(
                    0L,
                    name,
                    type,
                    environment.name(),
                    0.0, 0.0, 0.0,
                    0.0f, 0.0f,
                    false,
                    true,
                    null
            );
            return CompletableFuture.completedFuture(Optional.of(snapshot));
        } catch (final java.io.IOException ex) {
            logger.error("Failed to declare world '{}' in bukkit.yml: {}", name, ex.getMessage());
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    /**
     * Creates a NORMAL companion world on Folia using the RuntimeWorldLoader SPI.
     * <p>
     * On Folia, NORMAL worlds are created as companions to the server's default world
     * (the first world in Bukkit's world list). The companion name follows the pattern
     * {@code <parent>_<requested_name>}.
     * <p>
     * This method:
     * <ol>
     *   <li>Resolves the default world as the parent</li>
     *   <li>Generates the companion name using {@link CompanionWorldNameGenerator}</li>
     *   <li>Checks for existing companion world (conflict detection)</li>
     *   <li>Writes level.dat skeleton via {@link LevelDatBuilder}</li>
     *   <li>Invokes {@link RuntimeWorldLoader#loadWorld} to create the companion</li>
     *   <li>Adopts the loaded world into JExMultiverse</li>
     * </ol>
     * <p>
     * If the RuntimeWorldLoader is not available, falls back to the bukkit.yml
     * pending-restart path.
     *
     * @param requestedName the requested world name (not the companion name)
     * @param type          the world generation type (VOID, PLOT, DEFAULT)
     * @return a future containing the companion world snapshot, or empty on failure
     * @see RuntimeWorldLoaderResolver
     * @see CompanionWorldNameGenerator
     */
    private @NotNull CompletableFuture<Optional<MVWorldSnapshot>> createNormalCompanion(
            @NotNull String requestedName,
            @NotNull MVWorldType type) {
        
        // Step 1: Get the default world (parent for NORMAL companions)
        final World defaultWorld = getDefaultWorld();
        if (defaultWorld == null) {
            logger.error("Cannot create NORMAL companion '{}': no default world found", requestedName);
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        // Step 2: Generate companion name: <parent>_<requested_name>
        final String parentName = defaultWorld.getName();
        final String companionName = CompanionWorldNameGenerator.generateCompanionName(
                parentName, requestedName);
        
        logger.debug("[worlds] Creating NORMAL companion '{}' (parent: '{}', requested: '{}')",
                companionName, parentName, requestedName);
        
        // Step 3: Check if companion already exists (conflict detection)
        final World existingCompanion = Bukkit.getWorld(companionName);
        if (existingCompanion != null) {
            logger.info("[worlds] Companion world '{}' already exists, adopting", companionName);
            return adoptLoadedWorld(existingCompanion, type);
        }
        
        // Step 4: Check cache for companion name
        final var cachedCompanion = worldFactory.getCachedWorld(companionName);
        if (cachedCompanion.isPresent()) {
            logger.info("[worlds] Companion world '{}' already in cache, returning existing", companionName);
            return CompletableFuture.completedFuture(
                    Optional.of(cachedCompanion.get().toSnapshot()));
        }
        
        // Step 5: Write level.dat skeleton for companion world
        try {
            LevelDatBuilder.writeSkeleton(companionName, World.Environment.NORMAL);
        } catch (final java.io.IOException ex) {
            logger.error("Failed to write skeleton for companion '{}': {}", companionName, ex.getMessage());
            return CompletableFuture.completedFuture(Optional.empty());
        }
        
        // Step 6: Invoke RuntimeWorldLoader to create companion via NMS
        final Optional<RuntimeWorldLoader> loaderOpt = RuntimeWorldLoaderResolver.resolve(logger);
        if (loaderOpt.isEmpty()) {
            logger.warn("[worlds] No RuntimeWorldLoader available for '{}', falling back to pending-restart",
                    companionName);
            return ensureViaBukkitYml(companionName, World.Environment.NORMAL, type);
        }
        
        final RuntimeWorldLoader loader = loaderOpt.get();
        final CompletableFuture<World> loadFuture;
        try {
            loadFuture = loader.loadWorld(companionName, World.Environment.NORMAL);
        } catch (final java.io.IOException ex) {
            logger.error("[worlds] level.dat missing for companion '{}': {} — falling back to pending-restart",
                    companionName, ex.getMessage());
            return ensureViaBukkitYml(companionName, World.Environment.NORMAL, type);
        }
        return loadFuture
                .thenCompose(world -> {
                    logger.info("[worlds] Created NORMAL companion '{}' at runtime via {} backend",
                            companionName, loader.backendId());
                    return adoptLoadedWorld(world, type);
                })
                .exceptionally(ex -> {
                    if (!runtimeLoadFailureLogged) {
                        runtimeLoadFailureLogged = true;
                        logger.warn("[worlds] runtime companion-load unavailable via {} backend: {} -- '{}' will be created on next server start",
                                loader.backendId(), rootMessage(ex), companionName);
                    } else {
                        logger.debug("[worlds] companion-load failed for '{}' (already-known limitation): {}",
                                companionName, rootMessage(ex));
                    }
                    // On failure, try the bukkit.yml fallback path
                    try {
                        return ensureViaBukkitYml(companionName, World.Environment.NORMAL, type).join();
                    } catch (final Throwable fallbackEx) {
                        logger.error("[worlds] Fallback to bukkit.yml also failed for '{}': {}",
                                companionName, rootMessage(fallbackEx));
                        return Optional.empty();
                    }
                });
    }

    private @NotNull CompletableFuture<Optional<MVWorldSnapshot>> adoptLoadedWorld(
            @NotNull World bukkitWorld,
            @NotNull MVWorldType type) {
        final String worldName = bukkitWorld.getName();
        
        // First check if this world already exists in the database
        return repository.findByIdentifierAsync(worldName).thenCompose(existing -> {
            if (existing.isPresent()) {
                // World already in database - cache it and return
                worldFactory.cacheWorld(existing.get());
                logger.info("[worlds] '{}' already persisted — reusing existing record", worldName);
                return CompletableFuture.completedFuture(Optional.of(existing.get().toSnapshot()));
            }
            
            // World not in database - create new record
            final var spawn = worldFactory.getDefaultSpawnForType(bukkitWorld, type);
            final var mvWorld = MVWorld.builder()
                    .identifier(worldName)
                    .type(type)
                    .environment(bukkitWorld.getEnvironment())
                    .spawnLocation(spawn)
                    .globalizedSpawn(false)
                    .pvpEnabled(true)
                    .build();

            final var future = new CompletableFuture<Optional<MVWorldSnapshot>>();
            repository.saveWorld(mvWorld).thenAccept(saved -> {
                worldFactory.cacheWorld(saved);
                logger.info("[worlds] adopted runtime-loaded world '{}' into JExMultiverse", worldName);
                future.complete(Optional.of(saved.toSnapshot()));
            }).exceptionally(ex -> {
                // Persist failure is non-fatal here: the world IS loaded,
                // and a subsequent ensureWorld will see Bukkit.getWorld(name)
                // != null and short-circuit (caller path 1 in ensureWorld).
                logger.error("[worlds] runtime-loaded world '{}' but failed to persist: {}",
                        worldName, rootMessage(ex));
                future.complete(Optional.empty());
                return null;
            });
            return future;
        });
    }

    /**
     * Unwraps {@code CompletionException}/{@code ExecutionException} to
     * surface the underlying message in logs.
     */
    private static @NotNull String rootMessage(@NotNull Throwable t) {
        Throwable cur = t;
        while ((cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException)
                && cur.getCause() != null) {
            cur = cur.getCause();
        }
        final String msg = cur.getMessage();
        return msg != null ? msg : cur.getClass().getSimpleName();
    }

    @Override
    public boolean isRoadOrBorder(@NotNull Location location) {
        var w = location.getWorld();
        if (w == null) return false;
        var mv = worldFactory.getCachedWorld(w.getName());
        if (mv.isEmpty() || mv.get().getType() != MVWorldType.PLOT) return false;
        return plotAt(location).isEmpty();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> spawn(@NotNull Player player) {
        return getSpawnLocation(player).thenApply(location -> {
            if (location == null) return false;
            // Player teleport touches entity state — Folia requires the
            // player's region thread. runAtEntity collapses to main on
            // Paper. Bukkit.getScheduler() would throw UOE on Folia.
            scheduler.runAtEntity(player, () -> player.teleport(location));
            return true;
        }).exceptionally(ex -> {
            logger.error("Failed to teleport player '{}' to spawn", player.getName(), ex);
            return false;
        });
    }
}
