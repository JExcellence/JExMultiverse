package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.service.MultiverseService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * JExCommand 2.0 handler for {@code /spawn}.
 *
 * <p>Teleports the executing player to the resolved spawn location
 * (global spawn > world spawn > default world spawn).
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class SpawnHandler {

    private final MultiverseService service;
    private final JavaPlugin plugin;

    public SpawnHandler(@NotNull MultiverseService service, @NotNull JavaPlugin plugin) {
        this.service = service;
        this.plugin = plugin;
    }

    /**
     * Returns the command handler map for this handler, mapping {@code "spawn"} to its executor.
     *
     * @return map of command name to {@link CommandHandler}
     */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.of("spawn", this::onSpawn);
    }

    private void onSpawn(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        R18nManager.getInstance().msg("spawn.teleporting_to_spawn").prefix().send(player);

        service.getSpawnLocation(player).thenAccept(location -> {
            if (location == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        R18nManager.getInstance().msg("spawn.spawn_not_found").prefix().send(player));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                ensureSafeLanding(location);
                player.teleport(location);
                R18nManager.getInstance().msg("spawn.teleported").prefix().send(player);
            });
        }).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () ->
                    R18nManager.getInstance().msg("spawn.teleport_failed").prefix().send(player));
            return null;
        });
    }

    private void ensureSafeLanding(@NotNull Location location) {
        Block below = location.clone().subtract(0, 1, 0).getBlock();
        if (below.getType().isAir()) {
            below.setType(Material.GLASS);
        }
    }
}
