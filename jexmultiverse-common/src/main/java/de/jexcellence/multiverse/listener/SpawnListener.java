package de.jexcellence.multiverse.listener;

import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.service.MultiverseService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Listener that overrides player spawn and respawn locations using the
 * multiverse spawn resolution chain:
 * <ol>
 *   <li>Global spawn world's spawn location</li>
 *   <li>Current world's multiverse spawn</li>
 *   <li>Default Bukkit world spawn</li>
 * </ol>
 *
 * <p>Bed and respawn anchor locations are respected during respawn events
 * and take priority over multiverse spawn resolution.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class SpawnListener implements Listener {

    private final MultiverseService service;
    private final WorldFactory factory;
    private final JExLogger logger;

    public SpawnListener(@NotNull MultiverseService service,
                         @NotNull WorldFactory factory,
                         @NotNull JExLogger logger) {
        this.service = service;
        this.factory = factory;
        this.logger = logger;
    }

    // ── Initial join spawn ──────────────────────────────────────────────────────

    /**
     * Overrides the spawn location when a player joins the server.
     * Uses global spawn if available, otherwise falls back through the chain.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerSpawnLocation(@NotNull PlayerSpawnLocationEvent event) {
        var player = event.getPlayer();

        // First-time join: always send to global spawn if available
        if (!player.hasPlayedBefore()) {
            var globalSpawn = resolveGlobalSpawn();
            if (globalSpawn != null) {
                event.setSpawnLocation(globalSpawn);
                logger.debug("Set first-join spawn for '{}' to global spawn", player.getName());
                return;
            }
        }

        // Returning player: resolve through the chain
        var resolved = resolveWorldSpawn(player.getWorld().getName());
        if (resolved != null) {
            event.setSpawnLocation(resolved);
            logger.debug("Set join spawn for '{}' to multiverse spawn", player.getName());
        }
    }

    // ── Respawn ─────────────────────────────────────────────────────────────────

    /**
     * Overrides the respawn location when a player dies. Bed and respawn anchor
     * locations take priority; otherwise the multiverse spawn chain is used.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerRespawn(@NotNull PlayerRespawnEvent event) {
        var player = event.getPlayer();

        // Respect bed spawn if the player has a valid bed
        if (hasBedSpawn(player)) {
            var bedSpawn = player.getRespawnLocation();
            if (bedSpawn != null && isBedValid(bedSpawn)) {
                logger.debug("Player '{}' respawning at bed location", player.getName());
                return; // Let vanilla handle bed respawn
            }
        }

        // Respect respawn anchor if applicable (Nether)
        if (hasRespawnAnchor(player)) {
            logger.debug("Player '{}' respawning at anchor location", player.getName());
            return; // Let vanilla handle anchor respawn
        }

        // Resolve through multiverse spawn chain
        var worldName = player.getWorld().getName();
        var resolved = resolveWorldSpawn(worldName);
        if (resolved == null) {
            resolved = resolveGlobalSpawn();
        }
        if (resolved == null) {
            resolved = Bukkit.getWorlds().getFirst().getSpawnLocation();
        }

        event.setRespawnLocation(resolved);
        logger.debug("Set respawn for '{}' to multiverse spawn in '{}'",
                player.getName(), resolved.getWorld() != null ? resolved.getWorld().getName() : "unknown");
    }

    // ── Spawn resolution helpers ────────────────────────────────────────────────

    /**
     * Resolves the spawn location for a specific world from the cache.
     *
     * @param worldName the world name
     * @return the spawn location, or {@code null} if not found
     */
    private @Nullable Location resolveWorldSpawn(@NotNull String worldName) {
        return factory.getCachedWorld(worldName)
                .map(MVWorld::getSpawnLocation)
                .orElse(null);
    }

    /**
     * Resolves the global spawn location from the cache.
     *
     * @return the global spawn location, or {@code null} if none is set
     */
    private @Nullable Location resolveGlobalSpawn() {
        return factory.getAllCachedWorlds().stream()
                .filter(MVWorld::isGlobalizedSpawn)
                .findFirst()
                .map(MVWorld::getSpawnLocation)
                .orElse(null);
    }

    // ── Bed & anchor detection ──────────────────────────────────────────────────

    /**
     * Checks whether the player has a valid bed spawn location set.
     */
    private boolean hasBedSpawn(@NotNull Player player) {
        var bedSpawn = player.getRespawnLocation();
        return bedSpawn != null && bedSpawn.getWorld() != null;
    }

    /**
     * Validates that the bed block at the given location is still intact.
     */
    private boolean isBedValid(@NotNull Location location) {
        var block = location.getBlock();
        var data = block.getBlockData();
        if (data instanceof Bed) {
            return true;
        }
        // Check surrounding blocks for bed parts
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var nearby = block.getRelative(dx, 0, dz);
                if (nearby.getBlockData() instanceof Bed) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the player has a valid respawn anchor in their death world.
     * Respawn anchors only function in the Nether.
     */
    private boolean hasRespawnAnchor(@NotNull Player player) {
        var bedSpawn = player.getRespawnLocation();
        if (bedSpawn == null || bedSpawn.getWorld() == null) return false;

        var block = bedSpawn.getBlock();
        return block.getType() == Material.RESPAWN_ANCHOR;
    }
}
