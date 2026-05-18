package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.generator.plot.PlotSchematicPopulator;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.SchematicService;
import de.jexcellence.multiverse.view.MultiverseEditorView;
import de.jexcellence.multiverse.view.MultiverseListView;
import me.devnatan.inventoryframework.ViewFrame;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Random;

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

    private static final String KEY_WORLD_NAME  = "world_name";
    private static final String KEY_ENVIRONMENT = "environment";
    private static final String KEY_SCHEMATIC   = "schematic";
    private static final String KEY_WORLD       = "world";
    private static final String KEY_COUNT       = "count";
    private static final String KEY_ALIAS       = "alias";

    private static final Random RANDOM = new Random();

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
                "multiverse",                this::onRoot,
                "multiverse.create",         this::onCreate,
                "multiverse.delete",         this::onDelete,
                "multiverse.edit",           this::onEdit,
                "multiverse.teleport",       this::onTeleport,
                "multiverse.load",           this::onLoad,
                "multiverse.list",           this::onList,
                "multiverse.help",           this::onHelp,
                "multiverse.applyschematic", this::onApplySchematic
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
        var plotSize = ctx.get("plot_size", Long.class).map(Long::intValue).orElse(null);
        var roadWidth = ctx.get("road_width", Long.class).map(Long::intValue).orElse(null);
        var schematic = ctx.get("schematic", String.class).orElse(null);

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

        if (worldFactory.getCachedWorld(name).isPresent()
                || Bukkit.getWorld(name) != null) {
            r18n().msg("multiverse.world_already_exists").prefix()
                    .with(KEY_WORLD_NAME, name)
                    .send(sender);
            return;
        }

        r18n().msg("multiverse.creating_world").prefix()
                .with(KEY_WORLD_NAME, name)
                .send(sender);

        service.createWorld(name, environment, worldType, plotSize, roadWidth, schematic).thenAccept(opt -> {
            PlatformScheduler.of(plugin).runSync(() -> {
                if (opt.isPresent()) {
                    r18n().msg("multiverse.create_success").prefix()
                            .with(KEY_WORLD_NAME, name)
                            .with(KEY_ENVIRONMENT, environment.name())
                            .with("type", worldType.name())
                            .send(sender);
                } else {
                    r18n().msg("multiverse.create_failed").prefix()
                            .with(KEY_WORLD_NAME, name)
                            .send(sender);
                }
            });
        }).exceptionally(ex -> {
            PlatformScheduler.of(plugin).runSync(() ->
                    r18n().msg("multiverse.create_failed").prefix()
                            .with(KEY_WORLD_NAME, name)
                            .send(sender));
            return null;
        });
    }

    // ── Delete ──────────────────────────────────────────────────────────────────

    private void onDelete(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var world = ctx.require("world", MVWorld.class);

        r18n().msg("multiverse.deleting_world").prefix()
                .with(KEY_WORLD_NAME, world.getIdentifier())
                .send(sender);

        service.deleteWorld(world.getIdentifier()).thenAccept(success -> {
            PlatformScheduler.of(plugin).runSync(() -> {
                if (success) {
                    r18n().msg("multiverse.delete_success").prefix()
                            .with(KEY_WORLD_NAME, world.getIdentifier())
                            .send(sender);
                } else {
                    r18n().msg("multiverse.delete_failed").prefix()
                            .with(KEY_WORLD_NAME, world.getIdentifier())
                            .send(sender);
                }
            });
        }).exceptionally(ex -> {
            PlatformScheduler.of(plugin).runSync(() ->
                    r18n().msg("multiverse.delete_failed").prefix()
                            .with(KEY_WORLD_NAME, world.getIdentifier())
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
                "service", service
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
                    .with(KEY_WORLD_NAME, world.getIdentifier())
                    .send(player);
            return;
        }

        var spawnLocation = world.getSpawnLocation();
        var target = spawnLocation != null ? spawnLocation : bukkitWorld.get().getSpawnLocation();

        r18n().msg("multiverse.teleporting").prefix()
                .with(KEY_WORLD_NAME, world.getIdentifier())
                .send(player);

        PlatformScheduler.of(plugin).runSync(() -> {
            player.teleportAsync(target);
            r18n().msg("multiverse.teleported").prefix()
                    .with(KEY_WORLD_NAME, world.getIdentifier())
                    .send(player);
        });
    }

    // ── Load ────────────────────────────────────────────────────────────────────

    private void onLoad(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var world = ctx.require("world", MVWorld.class);

        if (worldFactory.isWorldLoaded(world.getIdentifier())) {
            r18n().msg("multiverse.already_loaded").prefix()
                    .with(KEY_WORLD_NAME, world.getIdentifier())
                    .send(sender);
            return;
        }

        r18n().msg("multiverse.loading_world").prefix()
                .with(KEY_WORLD_NAME, world.getIdentifier())
                .send(sender);

        PlatformScheduler.of(plugin).runSync(() -> {
            var loaded = worldFactory.loadWorld(world);
            if (loaded != null) {
                r18n().msg("multiverse.load_success").prefix()
                        .with(KEY_WORLD_NAME, world.getIdentifier())
                        .send(sender);
            } else {
                r18n().msg("multiverse.load_failed").prefix()
                        .with(KEY_WORLD_NAME, world.getIdentifier())
                        .send(sender);
            }
        });
    }

    // ── List ────────────────────────────────────────────────────────────────────

    private void onList(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var worlds = worldFactory.getAllCachedWorlds();

        // Players: open the paginated GUI even when empty — pagination handles
        // the empty state gracefully and the user can see they have 0 worlds.
        // Console: print the text view.
        var playerOpt = ctx.asPlayer();
        if (playerOpt.isPresent()) {
            try {
                viewFrame.open(MultiverseListView.class, playerOpt.get(), Map.of(
                        "plugin",  plugin,
                        "service", service,
                        "factory", worldFactory
                ));
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Failed to open multiverse list view", t);
                r18n().msg("multiverse.list_header").prefix()
                        .with(KEY_COUNT, String.valueOf(worlds.size()))
                        .send(sender);
                if (worlds.isEmpty()) {
                    r18n().msg("multiverse.list_empty").prefix().send(sender);
                }
            }
            return;
        }

        if (worlds.isEmpty()) {
            r18n().msg("multiverse.list_empty").prefix().send(sender);
            return;
        }

        r18n().msg("multiverse.list_header").prefix()
                .with(KEY_COUNT, String.valueOf(worlds.size()))
                .send(sender);

        for (var world : worlds) {
            var loaded = worldFactory.isWorldLoaded(world.getIdentifier());
            r18n().msg("multiverse.list_entry")
                    .with(KEY_WORLD_NAME, world.getIdentifier())
                    .with("type", world.getType().name())
                    .with(KEY_ENVIRONMENT, world.getEnvironment().name())
                    .with("status", loaded ? "loaded" : "unloaded")
                    .with("global_spawn", world.isGlobalizedSpawn() ? "yes" : "no")
                    .send(sender);
        }

        r18n().msg("multiverse.list_footer")
                .with(KEY_COUNT, String.valueOf(worlds.size()))
                .with("max", service.getMaxWorlds() < 0 ? "unlimited" : String.valueOf(service.getMaxWorlds()))
                .send(sender);
    }

    // ── Apply schematic ─────────────────────────────────────────────────────────

    private void onApplySchematic(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var world = ctx.require("world", MVWorld.class);
        if (world.getType() != MVWorldType.PLOT) {
            r18n().msg("multiverse.applyschematic_not_plot").prefix()
                    .with(KEY_WORLD_NAME, world.getIdentifier())
                    .send(sender);
            return;
        }

        var name = ctx.get(KEY_SCHEMATIC, String.class).orElse(world.getSchematicName());
        if (name == null || name.isBlank()) {
            r18n().msg("multiverse.applyschematic_missing_name").prefix()
                    .with(KEY_WORLD_NAME, world.getIdentifier())
                    .send(sender);
            return;
        }

        var bukkit = Bukkit.getWorld(world.getIdentifier());
        if (bukkit == null) {
            r18n().msg("multiverse.world_not_loaded").prefix()
                    .with(KEY_WORLD_NAME, world.getIdentifier())
                    .send(sender);
            return;
        }

        var schematicService = worldFactory.schematics();
        if (schematicService.load(name).isEmpty()) {
            r18n().msg("multiverse.applyschematic_not_found").prefix()
                    .with(KEY_SCHEMATIC, name)
                    .send(sender);
            return;
        }

        int plotSize = worldFactory.effectivePlotSize(world);
        int roadWidth = worldFactory.effectiveRoadWidth(world);
        int plotHeight = worldFactory.plotConfig().plotHeight();
        int interval = plotSize + roadWidth;

        var chunks = bukkit.getLoadedChunks();
        int placed = placeSchematicsInChunks(chunks, schematicService, name, bukkit,
                plotSize, roadWidth, plotHeight, interval);

        r18n().msg("multiverse.applyschematic_done").prefix()
                .with(KEY_WORLD_NAME, world.getIdentifier())
                .with(KEY_SCHEMATIC, name)
                .with(KEY_COUNT, String.valueOf(placed))
                .with("chunks", String.valueOf(chunks.length))
                .send(sender);
    }

    private int placeSchematicsInChunks(org.bukkit.Chunk @NotNull [] chunks,
                                        @NotNull SchematicService schematicService,
                                        @NotNull String name, @NotNull World bukkit,
                                        int plotSize, int roadWidth, int plotHeight,
                                        int interval) {
        int placed = 0;
        for (var chunk : chunks) {
            int chunkMinX = chunk.getX() << 4;
            int chunkMinZ = chunk.getZ() << 4;
            int chunkMaxX = chunkMinX + 15;
            int chunkMaxZ = chunkMinZ + 15;
            for (int gridX = Math.floorDiv(chunkMinX, interval);
                 gridX <= Math.floorDiv(chunkMaxX, interval); gridX++) {
                for (int gridZ = Math.floorDiv(chunkMinZ, interval);
                     gridZ <= Math.floorDiv(chunkMaxZ, interval); gridZ++) {
                    int anchorX = gridX * interval;
                    int anchorZ = gridZ * interval;
                    if (anchorX >= chunkMinX && anchorX <= chunkMaxX
                            && anchorZ >= chunkMinZ && anchorZ <= chunkMaxZ) {
                        PlotSchematicPopulator.placeManually(
                                schematicService, name, bukkit, gridX, gridZ,
                                plotSize, roadWidth, plotHeight, RANDOM);
                        placed++;
                    }
                }
            }
        }
        return placed;
    }

    // ── Help ────────────────────────────────────────────────────────────────────

    private void onHelp(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var alias = ctx.alias();

        r18n().msg("multiverse.help_header").send(sender);

        if (hasPerm(sender, "jexmultiverse.command.create")) {
            r18n().msg("multiverse.help_create").with(KEY_ALIAS, alias).send(sender);
        }
        if (hasPerm(sender, "jexmultiverse.command.delete")) {
            r18n().msg("multiverse.help_delete").with(KEY_ALIAS, alias).send(sender);
        }
        if (hasPerm(sender, "jexmultiverse.command.edit")) {
            r18n().msg("multiverse.help_edit").with(KEY_ALIAS, alias).send(sender);
        }
        if (hasPerm(sender, "jexmultiverse.command.teleport")) {
            r18n().msg("multiverse.help_teleport").with(KEY_ALIAS, alias).send(sender);
        }
        if (hasPerm(sender, "jexmultiverse.command.load")) {
            r18n().msg("multiverse.help_load").with(KEY_ALIAS, alias).send(sender);
        }
        if (hasPerm(sender, "jexmultiverse.command.list")) {
            r18n().msg("multiverse.help_list").with(KEY_ALIAS, alias).send(sender);
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
