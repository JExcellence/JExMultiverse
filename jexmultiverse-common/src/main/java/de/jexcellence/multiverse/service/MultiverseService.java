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

        // On Folia, Bukkit.createWorld throws UOE — route through the NMS-based
        // ensureViaNms path (same code path used by ensureWorld). The PLOT
        // overrides (plotSize/roadWidth/schematic) aren't yet plumbed through
        // the NMS factory; for now they're silently dropped on Folia and the
        // world is created with the type's default generator. Warn so the
        // operator knows.
        if (ServerDetector.detect() instanceof ServerType.Folia) {
            if (effectivePlotSize != null || effectiveRoadWidth != null || effectiveSchematic != null) {
                logger.warn("[worlds] PLOT overrides (size/road/schematic) not yet supported on Folia — creating '{}' with defaults",
                        name);
            }
            return ensureViaNms(name, environment, type).thenCompose(snapOpt -> {
                if (snapOpt.isEmpty()) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                // Re-hydrate from cache (ensureViaNms persists via adoptLoadedWorld)
                return CompletableFuture.completedFuture(worldFactory.getCachedWorld(name));
            });
        }

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
        // Adopt any loaded world that JExMultiverse doesn't yet have a row for.
        // This catches:
        //   * Bukkit auto-companions (<world>_nether, <world>_the_end) that the
        //     server boots automatically — we skip them so they're not treated
        //     as standalone managed worlds.
        //   * Worlds the operator created manually (e.g. via server.properties
        //     or a previous plugin install).
        //   * Worlds that were created at runtime on a prior session but whose
        //     MVWorld row failed to persist.

        logger.debug("[startup] Scanning for loaded worlds to adopt...");
        var adoptionFutures = new java.util.ArrayList<CompletableFuture<Void>>();
        for (var world : Bukkit.getWorlds()) {
            var worldName = world.getName();

            // Skip if already cached
            if (worldFactory.getCachedWorld(worldName).isPresent()) {
                continue;
            }

            // Skip Bukkit's auto-pair dimensional siblings — those belong to
            // their overworld parent, not standalone management.
            if (worldName.endsWith("_nether") || worldName.endsWith("_the_end") || worldName.endsWith("_end")) {
                continue;
            }

            logger.info("[startup] Adopting unmanaged loaded world '{}' into JExMultiverse", worldName);
            
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
        // - On Folia: NMS-based runtime world creation via RuntimeWorldLoader SPI
        //   (Bukkit.createWorld is patched to throw UOE; the loader bypasses
        //   that by going directly through MinecraftServer.addLevel).
        // - On Paper/Spigot: standard Bukkit.createWorld path.
        if (isFolia) {
            return ensureViaNms(name, environment, type);
        }
        // Paper/Spigot: NORMAL worlds use primary world creation.
        return createWorld(name, environment, type).thenApply(opt ->
                opt.map(MVWorld::toSnapshot));
    }

    /**
     * Folia path: invokes the {@link RuntimeWorldLoader} SPI which reconstructs
     * a {@code ServerLevel} via NMS reflection and registers it with the
     * underlying {@code MinecraftServer}. The level.dat skeleton is written
     * first so the storage layer has a valid world directory to read from.
     *
     * <p>On loader failure, returns {@link Optional#empty()} with a clear
     * error log. There is intentionally no bukkit.yml/restart fallback —
     * if Folia runtime world creation doesn't work, the operator needs to
     * see the failure, not silently end up in a half-broken pending-restart
     * state.
     */
    private @NotNull CompletableFuture<Optional<MVWorldSnapshot>> ensureViaNms(
            @NotNull String name,
            World.@NotNull Environment environment,
            @NotNull MVWorldType type) {
        final Optional<RuntimeWorldLoader> backend = RuntimeWorldLoaderResolver.resolve(logger);
        if (backend.isEmpty()) {
            logger.error("[worlds] no RuntimeWorldLoader available — cannot create '{}' on Folia. " +
                    "The folia-nms module must be on the classpath.", name);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Step 1: write the on-disk skeleton so the NMS storage source can
        // open the directory. Idempotent — re-runs are safe.
        try {
            final java.io.File worldContainer = Bukkit.getWorldContainer();
            final java.io.File worldDir = new java.io.File(worldContainer, name);
            if (!worldDir.exists() || !new java.io.File(worldDir, "level.dat").exists()) {
                LevelDatBuilder.writeSkeleton(name, environment);
            } else {
                // Clean up stale uid.dat so the NMS layer doesn't trip on a
                // UUID collision with a previously-attempted load.
                final java.io.File uidDat = new java.io.File(worldDir, "uid.dat");
                if (uidDat.exists()) uidDat.delete();
            }
        } catch (final java.io.IOException ex) {
            logger.error("[worlds] failed to write skeleton for '{}': {}", name, ex.getMessage());
            return CompletableFuture.completedFuture(Optional.empty());
        }

        // Step 2: declare the generator in bukkit.yml so the override applies
        // when the loaded world's chunks are generated. This is config-only —
        // doesn't trigger any restart-required path.
        try {
            BukkitYmlWriter.declare(name, GeneratorRegistry.voidGeneratorRef(), logger);
        } catch (final java.io.IOException ex) {
            logger.warn("[worlds] bukkit.yml write failed for '{}' (non-fatal): {}", name, ex.getMessage());
        }

        // Step 3: hand off to the loader. Note: loadWorld() is responsible for
        // doing its OWN thread-hop to the global region; we must NOT block
        // here on the caller thread.
        final RuntimeWorldLoader loader = backend.get();
        final CompletableFuture<World> loadFuture;
        try {
            loadFuture = loader.loadWorld(name, environment);
        } catch (final java.io.IOException ex) {
            logger.error("[worlds] loader rejected '{}': {}", name, ex.getMessage());
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return loadFuture
                .thenCompose(world -> {
                    logger.info("[worlds] '{}' created at runtime via {} backend",
                            name, loader.backendId());
                    return adoptLoadedWorld(world, type);
                })
                .exceptionally(ex -> {
                    if (!runtimeLoadFailureLogged) {
                        runtimeLoadFailureLogged = true;
                        logger.error("[worlds] runtime load failed via {} backend: {} — world '{}' not created",
                                loader.backendId(), rootMessage(ex), name);
                    } else {
                        logger.debug("[worlds] runtime load failed for '{}': {}", name, rootMessage(ex));
                    }
                    return Optional.empty();
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
