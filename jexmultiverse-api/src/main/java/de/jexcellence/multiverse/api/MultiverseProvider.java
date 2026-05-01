package de.jexcellence.multiverse.api;

import org.bukkit.Location;
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
     * no actual claim still return valid bounds — this is a pure geometry
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
}
