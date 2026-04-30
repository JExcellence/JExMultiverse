package de.jexcellence.multiverse.api;

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
}
