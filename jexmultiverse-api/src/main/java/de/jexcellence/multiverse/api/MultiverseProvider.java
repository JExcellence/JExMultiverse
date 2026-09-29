package de.jexcellence.multiverse.api;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for interacting with JExMultiverse from external plugins.
 * <p>
 * Obtain an instance via {@link JExMultiverseAPI#get()}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public interface MultiverseProvider {

    /**
     * Retrieves a world snapshot by its identifier.
     *
     * @param identifier the world name
     * @return a future containing the snapshot, or empty if not found
     */
    @NotNull CompletableFuture<Optional<MVWorldSnapshot>> getWorld(@NotNull String identifier);

    /**
     * Idempotently ensures a managed world exists. If the world is
     * already registered in JExMultiverse, this is a no-op that returns
     * the existing snapshot. If it is not registered, the world is
     * created, persisted, and loaded - matching the behaviour of
     * {@code /mv create} but invokable programmatically.
     *
     * <p>Use case: sister plugins that need their own world (JExOneblock
     * for {@code oneblock_overworld}, etc.) call this on enable so the
     * world becomes part of JExMultiverse's loaded set on every
     * subsequent start. The plugin no longer has to manage its own
     * world bootstrap.
     *
     * <p>Folia-safe: world creation routes through PlatformScheduler
     * internally.
     *
     * <p>The future does not start work until JExMultiverse has finished loading its
     * persisted worlds at boot, so a world that is already persisted is adopted
     * instead of being created a second time. Callers must not block the main thread
     * on it during server startup.
     *
     * @param name        the world identifier (also the on-disk folder name)
     * @param environment the world environment (NORMAL / NETHER / THE_END)
     * @param type        the JExMultiverse world generation type
     * @return a future resolving to the snapshot, or empty when the
     *         creation failed (world limit reached, type not allowed in
     *         this edition, etc.)
     * @since 3.3.0
     */
    @NotNull CompletableFuture<Optional<MVWorldSnapshot>> ensureWorld(@NotNull String name,
                                                                       World.@NotNull Environment environment,
                                                                       @NotNull MVWorldType type);

    /**
     * Retrieves the world designated as the global spawn.
     *
     * @return a future containing the global spawn world, or empty if none is set
     */
    @NotNull CompletableFuture<Optional<MVWorldSnapshot>> getGlobalSpawnWorld();

    /**
     * Retrieves all managed worlds.
     *
     * @return a future containing a list of all world snapshots
     */
    @NotNull CompletableFuture<List<MVWorldSnapshot>> getAllWorlds();

    /**
     * Checks whether the given world has a configured multiverse spawn.
     *
     * @param worldName the world name
     * @return a future containing {@code true} if a spawn is configured
     */
    @NotNull CompletableFuture<Boolean> hasMultiverseSpawn(@NotNull String worldName);

    /**
     * Teleports a player to the appropriate spawn location.
     *
     * @param player the player to teleport
     * @return a future containing {@code true} if the teleport succeeded
     */
    @NotNull CompletableFuture<Boolean> spawn(@NotNull Player player);

    /**
     * Sets the JExMultiverse spawn of a world to the given location (the spawn
     * {@link #spawn(Player)} then teleports to, per the resolution priority).
     *
     * @param worldName the world identifier
     * @param location  the new spawn location
     * @return a future containing {@code true} if the spawn was set
     * @since 3.6.0
     */
    @NotNull CompletableFuture<Boolean> setSpawn(@NotNull String worldName, @NotNull Location location);

    /**
     * Returns whether the given Bukkit world is managed by JExMultiverse.
     *
     * <p>Synchronous cache lookup with no database access, so this is safe to call
     * from a main-thread event handler on a hot path. Prefer it over
     * {@link #getWorld(String)} when all you need is a yes or no.
     *
     * @param worldName the Bukkit world name
     * @return {@code true} if the world is managed
     * @since 3.7.0
     */
    boolean isManaged(@NotNull String worldName);

    /**
     * Regenerates a managed world in place, keeping its identity and settings.
     *
     * <p>Terrain is destroyed; the database row, identifier, spawn and generation
     * overrides all survive. For a {@link MVWorldType#PLOT} world the plot claims are
     * purged too. This is deliberately different from deleting and recreating a
     * world, and consumers should treat the two cases differently.
     *
     * @param identifier the managed world identifier
     * @param newSeed    whether the caller intends a fresh seed
     * @return a future completing with {@code true} if the world was regenerated
     * @since 3.7.0
     */
    @NotNull CompletableFuture<Boolean> resetWorld(@NotNull String identifier, boolean newSeed);

    /**
     * Copies a managed world, terrain and settings, under a new identifier.
     *
     * <p>The clone does not inherit the source world's global-spawn flag, because only
     * one world may hold it, and does not inherit plot claims.
     *
     * <p>The copy is always taken from disk. An unloaded source (the recommended state
     * for template worlds) is copied as is. A loaded source must be empty of players; it
     * is unloaded with a save for the copy and loaded again afterwards. Runtime files
     * such as {@code session.lock}, the world UUID and chunk tickets are never copied,
     * and the clone is loaded from the copied folder.
     *
     * @param source the world to copy
     * @param target the new identifier
     * @return a future completing with the clone's snapshot, or empty on failure
     * @since 3.7.0
     */
    @NotNull CompletableFuture<Optional<MVWorldSnapshot>> cloneWorld(@NotNull String source,
                                                                     @NotNull String target);

    /**
     * Unloads a managed world without deleting it.
     *
     * <p>Players in the world are moved to the default world's spawn. The world folder
     * and database row survive, so the world can be loaded again later.
     *
     * @param identifier the managed world identifier
     * @param save       whether to save chunks before unloading
     * @return a future completing with {@code true} if the world was unloaded
     * @since 3.7.0
     */
    @NotNull CompletableFuture<Boolean> unloadWorld(@NotNull String identifier, boolean save);

    /**
     * Deletes a world for good: unloads it without saving, removes its database row and
     * plot claims, and deletes its folder from disk.
     *
     * <p>The folder is resolved the same way the server lays worlds out (on 26.x that is
     * {@code <level>/dimensions/<namespace>/<name>}), so the folder that is deleted is the
     * folder the world was loaded from. An identifier without a database row is still
     * unloaded and its folder removed, which makes this safe for sweeping leftovers.
     * Players in the world are moved to the default world's spawn first.
     *
     * <p>Safe to call from any thread; the unload runs on the global region / main thread
     * and the file deletion runs asynchronously.
     *
     * @param identifier the world identifier
     * @return a future completing with {@code true} if the world is gone from disk
     * @since 3.7.0
     */
    @NotNull CompletableFuture<Boolean> deleteWorld(@NotNull String identifier);

    // ── Plot grid (PLOT-type worlds only) ──────────────────────────────────────

    /**
     * Returns the plot grid coordinates of the given location in a
     * {@link MVWorldType#PLOT} world, or empty if the location is not on a
     * plot (i.e. on a road, border, or in a non-plot world).
     *
     * <p>Lookup is synchronous and reads from the world cache; safe to call
     * from main-thread event handlers.
     *
     * @param location the world-space location to test
     * @return the plot coordinates, or empty if the location isn't on a plot
     * @since 3.1.0
     */
    @NotNull Optional<PlotCoord> plotAt(@NotNull Location location);

    /**
     * Returns the world-space bounds of a plot grid cell, or empty if the
     * given world isn't a {@link MVWorldType#PLOT} world. Coordinates with
     * no actual claim still return valid bounds - this is a pure geometry
     * lookup against the world's plot/road grid.
     *
     * @param worldIdentifier the JExMultiverse world identifier
     * @param gridX           plot grid X coordinate
     * @param gridZ           plot grid Z coordinate
     * @return plot bounds, or empty if the world isn't a plot world
     * @since 3.1.0
     */
    @NotNull Optional<PlotBounds> plotBounds(@NotNull String worldIdentifier, int gridX, int gridZ);

    /**
     * Returns {@code true} if the location is in a {@link MVWorldType#PLOT}
     * world AND on a road/border tile. Returns {@code false} for locations
     * inside a plot or in any non-plot world.
     *
     * @param location the world-space location to test
     * @return {@code true} if the location is on a road/border
     * @since 3.1.0
     */
    boolean isRoadOrBorder(@NotNull Location location);

    // ── Plot ownership (PLOT-type worlds, claimed plots only) ─────────────────

    /**
     * Returns ownership info for the claimed plot at the given location, or
     * empty if the location is on a road / unclaimed plot / non-plot world.
     *
     * @param location world-space location
     * @return plot ownership snapshot, or empty
     * @since 3.2.0
     */
    @NotNull java.util.Optional<PlotOwnership> plotOwnership(@NotNull Location location);

    /**
     * Returns whether the player is permitted to modify blocks at the given
     * location under JExMultiverse plot protection: owner / trusted member /
     * holds {@code jexplots.bypass.protect}, or the location isn't on a
     * claimed plot at all.
     *
     * <p>Listener-safe - synchronous read against the in-memory plot cache.
     *
     * @since 3.2.0
     */
    boolean canBuild(@NotNull org.bukkit.entity.Player player, @NotNull Location location);

    /**
     * Returns the effective boolean value of a plot flag at the given
     * location, or empty if no plot is claimed there. Flag keys match the
     * names used by {@code /plot flag set <key> <value>}: {@code pvp},
     * {@code mob-spawning}, {@code explosion}, {@code fire-spread},
     * {@code keep-inventory}, {@code entry}, {@code liquid-flow},
     * {@code ice-form-melt}.
     *
     * @since 3.2.0
     */
    @NotNull java.util.Optional<Boolean> getPlotFlag(@NotNull Location location, @NotNull String flagKey);
}
