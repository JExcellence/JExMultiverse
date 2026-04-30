package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.view.MultiverseEditorView;
import me.devnatan.inventoryframework.ViewFrame;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * JExCommand 2.0 handler collection for {@code /multiverse}.
 *
 * <p>Supports seven paths:
 * <ul>
 *   <li>{@code /multiverse}                           — show help</li>
 *   <li>{@code /multiverse create <name> [env] [type]} — create a world</li>
 *   <li>{@code /multiverse delete <world>}             — delete a world</li>
 *   <li>{@code /multiverse edit <world>}               — open editor GUI</li>
 *   <li>{@code /multiverse teleport <world>}           — teleport to world</li>
 *   <li>{@code /multiverse load <world>}               — load from database</li>
 *   <li>{@code /multiverse list}                       — list all worlds</li>
 *   <li>{@code /multiverse help}                       — usage printout</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class MultiverseHandler {

    private final MultiverseService service;
    private final WorldFactory worldFactory;
    private final ViewFrame viewFrame;
    private final JavaPlugin plugin;

    public MultiverseHandler(@NotNull MultiverseService service,
                             @NotNull WorldFactory worldFactory,
                             @NotNull ViewFrame viewFrame,
                             @NotNull JavaPlugin plugin) {
        this.service = service;
        this.worldFactory = worldFactory;
        this.viewFrame = viewFrame;
        this.plugin = plugin;
    }

    /** Returns the path to handler map for registration. */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.of(
                "multiverse",           this::onRoot,
                "multiverse.create",    this::onCreate,
                "multiverse.delete",    this::onDelete,
                "multiverse.edit",      this::onEdit,
                "multiverse.teleport",  this::onTeleport,
                "multiverse.load",      this::onLoad,
                "multiverse.list",      this::onList,
                "multiverse.help",      this::onHelp
        );
    }

    // ── Root ────────────────────────────────────────────────────────────────────

    private void onRoot(@NotNull CommandContext ctx) {
        onHelp(ctx);
    }

    // ── Create ──────────────────────────────────────────────────────────────────

    private void onCreate(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var name = ctx.require("name", String.class);
        var environment = ctx.get("environment", World.Environment.class).orElse(World.Environment.NORMAL);
        var worldType = ctx.get("type", MVWorldType.class).orElse(MVWorldType.DEFAULT);

        if (!service.isWorldTypeAvailable(worldType)) {
            r18n().msg("multiverse.type_not_available").prefix()
                    .with("type", worldType.name())
                    .send(sender);
            return;
        }

        if (service.isAtWorldLimit()) {
            r18n().msg("multiverse.world_limit_reached").prefix()
                    .with("max", String.valueOf(service.getMaxWorlds()))
                    .send(sender);
            return;
        }

        r18n().msg("multiverse.creating_world").prefix()
                .with("world_name", name)
                .send(sender);

        service.createWorld(name, environment, worldType).thenAccept(opt -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (opt.isPresent()) {
                    r18n().msg("multiverse.create_success").prefix()
                            .with("world_name", name)
                            .with("environment", environment.name())
                            .with("type", worldType.name())
                            .send(sender);
                } else {
                    r18n().msg("multiverse.create_failed").prefix()
                            .with("world_name", name)
                            .send(sender);
                }
            });
        }).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () ->
                    r18n().msg("multiverse.create_failed").prefix()
                            .with("world_name", name)
                            .send(sender));
            return null;
        });
    }

    // ── Delete ──────────────────────────────────────────────────────────────────

    private void onDelete(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var world = ctx.require("world", MVWorld.class);

        r18n().msg("multiverse.deleting_world").prefix()
                .with("world_name", world.getIdentifier())
                .send(sender);

        service.deleteWorld(world.getIdentifier()).thenAccept(success -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (success) {
                    r18n().msg("multiverse.delete_success").prefix()
                            .with("world_name", world.getIdentifier())
                            .send(sender);
                } else {
                    r18n().msg("multiverse.delete_failed").prefix()
                            .with("world_name", world.getIdentifier())
                            .send(sender);
                }
            });
        }).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () ->
                    r18n().msg("multiverse.delete_failed").prefix()
                            .with("world_name", world.getIdentifier())
                            .send(sender));
            return null;
        });
    }

    // ── Edit ────────────────────────────────────────────────────────────────────

    private void onEdit(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        var world = ctx.require("world", MVWorld.class);

        viewFrame.open(MultiverseEditorView.class, player, Map.of(
                "plugin", plugin,
                "world", world,
                "repository", service
        ));
    }

    // ── Teleport ────────────────────────────────────────────────────────────────

    private void onTeleport(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        var world = ctx.require("world", MVWorld.class);
        var bukkitWorld = worldFactory.getBukkitWorld(world.getIdentifier());

        if (bukkitWorld.isEmpty()) {
            r18n().msg("multiverse.world_not_loaded").prefix()
                    .with("world_name", world.getIdentifier())
                    .send(player);
            return;
        }

        var spawnLocation = world.getSpawnLocation();
        var target = spawnLocation != null ? spawnLocation : bukkitWorld.get().getSpawnLocation();

        r18n().msg("multiverse.teleporting").prefix()
                .with("world_name", world.getIdentifier())
                .send(player);

        Bukkit.getScheduler().runTask(plugin, () -> {
            player.teleport(target);
            r18n().msg("multiverse.teleported").prefix()
                    .with("world_name", world.getIdentifier())
                    .send(player);
        });
    }

    // ── Load ────────────────────────────────────────────────────────────────────

    private void onLoad(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var world = ctx.require("world", MVWorld.class);

        if (worldFactory.isWorldLoaded(world.getIdentifier())) {
            r18n().msg("multiverse.already_loaded").prefix()
                    .with("world_name", world.getIdentifier())
                    .send(sender);
            return;
        }

        r18n().msg("multiverse.loading_world").prefix()
                .with("world_name", world.getIdentifier())
                .send(sender);

        Bukkit.getScheduler().runTask(plugin, () -> {
            var loaded = worldFactory.loadWorld(world);
            if (loaded != null) {
                r18n().msg("multiverse.load_success").prefix()
                        .with("world_name", world.getIdentifier())
                        .send(sender);
            } else {
                r18n().msg("multiverse.load_failed").prefix()
                        .with("world_name", world.getIdentifier())
                        .send(sender);
            }
        });
    }

    // ── List ────────────────────────────────────────────────────────────────────

    private void onList(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var worlds = worldFactory.getAllCachedWorlds();

        if (worlds.isEmpty()) {
            r18n().msg("multiverse.list_empty").prefix().send(sender);
            return;
        }

        r18n().msg("multiverse.list_header").prefix()
                .with("count", String.valueOf(worlds.size()))
                .send(sender);

        for (var world : worlds) {
            var loaded = worldFactory.isWorldLoaded(world.getIdentifier());
            r18n().msg("multiverse.list_entry")
                    .with("world_name", world.getIdentifier())
                    .with("type", world.getType().name())
                    .with("environment", world.getEnvironment().name())
                    .with("status", loaded ? "loaded" : "unloaded")
                    .with("global_spawn", world.isGlobalizedSpawn() ? "yes" : "no")
                    .send(sender);
        }

        r18n().msg("multiverse.list_footer")
                .with("count", String.valueOf(worlds.size()))
                .with("max", service.getMaxWorlds() < 0 ? "unlimited" : String.valueOf(service.getMaxWorlds()))
                .send(sender);
    }

    // ── Help ────────────────────────────────────────────────────────────────────

    private void onHelp(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var alias = ctx.alias();

        r18n().msg("multiverse.help_header").send(sender);

        if (hasPerm(sender, "jexmultiverse.command.create")) {
            r18n().msg("multiverse.help_create").with("alias", alias).send(sender);
        }
        if (hasPerm(sender, "jexmultiverse.command.delete")) {
            r18n().msg("multiverse.help_delete").with("alias", alias).send(sender);
        }
        if (hasPerm(sender, "jexmultiverse.command.edit")) {
            r18n().msg("multiverse.help_edit").with("alias", alias).send(sender);
        }
        if (hasPerm(sender, "jexmultiverse.command.teleport")) {
            r18n().msg("multiverse.help_teleport").with("alias", alias).send(sender);
        }
        if (hasPerm(sender, "jexmultiverse.command.load")) {
            r18n().msg("multiverse.help_load").with("alias", alias).send(sender);
        }
        if (hasPerm(sender, "jexmultiverse.command.list")) {
            r18n().msg("multiverse.help_list").with("alias", alias).send(sender);
        }

        r18n().msg("multiverse.help_footer").send(sender);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static boolean hasPerm(@NotNull org.bukkit.command.CommandSender sender,
                                   @NotNull String node) {
        return sender instanceof Player p && (p.isOp() || p.hasPermission(node));
    }

    private static R18nManager r18n() {
        return R18nManager.getInstance();
    }
}
