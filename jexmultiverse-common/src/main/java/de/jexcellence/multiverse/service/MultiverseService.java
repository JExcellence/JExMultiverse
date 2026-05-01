package de.jexcellence.multiverse.service;

import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.multiverse.api.MVWorldSnapshot;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.api.MultiverseProvider;
import de.jexcellence.multiverse.api.PlotBounds;
import de.jexcellence.multiverse.api.PlotCoord;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.database.repository.MVWorldRepository;
import de.jexcellence.multiverse.factory.WorldFactory;
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

    public MultiverseService(@NotNull MultiverseEdition edition,
                             @NotNull MVWorldRepository repository,
                             @NotNull WorldFactory worldFactory,
                             @NotNull JExLogger logger,
                             @NotNull JavaPlugin plugin) {
        this.edition = edition;
        this.repository = repository;
        this.worldFactory = worldFactory;
        this.logger = logger;
        this.plugin = plugin;
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

        var future = new CompletableFuture<Optional<MVWorld>>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            var bukkitWorld = worldFactory.createBukkitWorld(name, environment, type);
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
        var future = new CompletableFuture<Boolean>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            var unloaded = worldFactory.unloadWorld(identifier, false);
            if (!unloaded) {
                future.complete(false);
                return;
            }

            repository.deleteByIdentifier(identifier)
                    .thenCompose(dbDeleted -> {
                        if (!dbDeleted) return CompletableFuture.completedFuture(false);
                        return worldFactory.deleteWorldFiles(identifier);
                    })
                    .thenAccept(success -> {
                        worldFactory.invalidateCache(identifier);
                        logger.info("Deleted world '{}'", identifier);
                        future.complete(success);
                    })
                    .exceptionally(ex -> {
                        logger.error("Failed to delete world '{}'", identifier, ex);
                        future.complete(false);
                        return null;
                    });
        });

        return future;
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
        var mv = worldFactory.getCachedWorld(name);
        if (mv.isEmpty() || mv.get().getType() != MVWorldType.PLOT) return Optional.empty();

        var cfg = worldFactory.plotConfig();
        int interval = cfg.plotSize() + cfg.roadWidth();
        int modX = Math.floorMod(location.getBlockX(), interval);
        int modZ = Math.floorMod(location.getBlockZ(), interval);
        if (modX >= cfg.plotSize() || modZ >= cfg.plotSize()) return Optional.empty();

        int gridX = Math.floorDiv(location.getBlockX(), interval);
        int gridZ = Math.floorDiv(location.getBlockZ(), interval);
        return Optional.of(new PlotCoord(name, gridX, gridZ));
    }

    @Override
    public @NotNull Optional<PlotBounds> plotBounds(@NotNull String worldIdentifier, int gridX, int gridZ) {
        var mv = worldFactory.getCachedWorld(worldIdentifier);
        if (mv.isEmpty() || mv.get().getType() != MVWorldType.PLOT) return Optional.empty();

        var cfg = worldFactory.plotConfig();
        int interval = cfg.plotSize() + cfg.roadWidth();
        int minX = gridX * interval;
        int minZ = gridZ * interval;
        int maxX = minX + cfg.plotSize() - 1;
        int maxZ = minZ + cfg.plotSize() - 1;
        return Optional.of(new PlotBounds(worldIdentifier, gridX, gridZ, minX, minZ, maxX, maxZ, cfg.plotHeight()));
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
            Bukkit.getScheduler().runTask(plugin, () -> player.teleport(location));
            return true;
        }).exceptionally(ex -> {
            logger.error("Failed to teleport player '{}' to spawn", player.getName(), ex);
            return false;
        });
    }
}
