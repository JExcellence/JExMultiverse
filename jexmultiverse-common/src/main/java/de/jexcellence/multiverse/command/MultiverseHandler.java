package de.jexcellence.multiverse.command;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.jexplatform.schematic.edit.SchematicEditor;
import de.jexcellence.jexplatform.schematic.edit.Selection;
import de.jexcellence.jexplatform.schematic.edit.SelectionService;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.api.MVWorldType;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.generator.plot.PlotSchematicPopulator;
import de.jexcellence.multiverse.generator.plot.PlotSchematicPopulator.PlacementParams;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.SchematicService;
import de.jexcellence.multiverse.view.MultiverseEditorView;
import de.jexcellence.multiverse.view.MultiverseListView;
import me.devnatan.inventoryframework.ViewFrame;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.BlockVector;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    private static final String MSG_EDIT_WORKING      = "multiverse.edit.working";
    private static final String MSG_EDIT_NO_CLIPBOARD = "multiverse.edit.no_clipboard";

    private static final Random RANDOM = new Random();

    private final MultiverseService service;
    private final WorldFactory worldFactory;
    private final ViewFrame viewFrame;
    private final JavaPlugin plugin;
    private final SelectionService selections;
    private final SchematicEditor editor;
    private final de.jexcellence.jexplatform.schematic.edit.SelectionBorderService selectionBorder;

    public MultiverseHandler(@NotNull MultiverseService service,
                             @NotNull WorldFactory worldFactory,
                             @NotNull ViewFrame viewFrame,
                             @NotNull JavaPlugin plugin,
                             @NotNull SelectionService selections,
                             @NotNull SchematicEditor editor,
                             @NotNull de.jexcellence.jexplatform.schematic.edit.SelectionBorderService selectionBorder) {
        this.service = service;
        this.worldFactory = worldFactory;
        this.viewFrame = viewFrame;
        this.plugin = plugin;
        this.selections = selections;
        this.editor = editor;
        this.selectionBorder = selectionBorder;
    }

    /** Returns the path to handler map for registration. */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.ofEntries(
                Map.entry("multiverse",                this::onRoot),
                Map.entry("multiverse.create",         this::onCreate),
                Map.entry("multiverse.delete",         this::onDelete),
                Map.entry("multiverse.edit",           this::onEdit),
                Map.entry("multiverse.teleport",       this::onTeleport),
                Map.entry("multiverse.load",           this::onLoad),
                Map.entry("multiverse.list",           this::onList),
                Map.entry("multiverse.help",           this::onHelp),
                Map.entry("multiverse.applyschematic", this::onApplySchematic),
                Map.entry("multiverse.paste",          this::onPaste),
                Map.entry("multiverse.wand",           this::onWand),
                Map.entry("multiverse.pos1",           this::onPos1),
                Map.entry("multiverse.pos2",           this::onPos2),
                Map.entry("multiverse.selection",      this::onSelectionToggle),
                Map.entry("multiverse.set",            this::onSet),
                Map.entry("multiverse.copy",           this::onCopy),
                Map.entry("multiverse.cut",            this::onCut),
                Map.entry("multiverse.save",           this::onSave),
                Map.entry("multiverse.rotate",         this::onRotate),
                Map.entry("multiverse.flip",           this::onFlip),
                Map.entry("multiverse.undo",           this::onUndo),
                Map.entry("multiverse.lock",           this::onLock),
                Map.entry("multiverse.unlock",         this::onUnlock),
                Map.entry("multiverse.build",          this::onBuild)
        );
    }

    // ── Root ────────────────────────────────────────────────────────────────────

    private void onRoot(@NotNull CommandContext ctx) {
        onHelp(ctx);
    }

    // ── Build-lock (a lightweight per-world protection) ───────────────────────────

    private void onLock(@NotNull CommandContext ctx) {
        setBuildLock(ctx, true);
    }

    private void onUnlock(@NotNull CommandContext ctx) {
        setBuildLock(ctx, false);
    }

    /**
     * Locks / unlocks the target world (defaults to the sender's current world).
     * A locked world blocks every player action except for operators and players
     * in build mode ({@code /mv build}).
     */
    private void setBuildLock(@NotNull CommandContext ctx, boolean locked) {
        var sender = ctx.sender();
        MVWorld world = ctx.get(KEY_WORLD, MVWorld.class).orElse(null);
        if (world == null) {
            var player = ctx.asPlayer().orElse(null);
            if (player == null) {
                r18n().msg("multiverse.lock_needs_world").prefix().send(sender);
                return;
            }
            world = worldFactory.getCachedWorld(player.getWorld().getName()).orElse(null);
            if (world == null) {
                r18n().msg("multiverse.lock_not_managed").prefix()
                        .with(KEY_WORLD_NAME, player.getWorld().getName()).send(sender);
                return;
            }
        }
        final String name = world.getIdentifier();
        if (world.isBuildLocked() == locked) {
            r18n().msg(locked ? "multiverse.lock_already" : "multiverse.unlock_already").prefix()
                    .with(KEY_WORLD_NAME, name).send(sender);
            return;
        }
        service.setBuildLocked(name, locked).thenAccept(ok -> PlatformScheduler.of(plugin).runSync(() ->
                r18n().msg(lockResultKey(locked, Boolean.TRUE.equals(ok))).prefix()
                        .with(KEY_WORLD_NAME, name).send(sender)));
    }

    private static @NotNull String lockResultKey(boolean locked, boolean ok) {
        if (!ok) {
            return "multiverse.lock_failed";
        }
        return locked ? "multiverse.lock_success" : "multiverse.unlock_success";
    }

    private void onBuild(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) {
            r18n().msg("multiverse.lock_needs_world").prefix().send(ctx.sender());
            return;
        }
        boolean enabled = service.buildMode().toggle(player);
        r18n().msg(enabled ? "multiverse.build_enabled" : "multiverse.build_disabled").prefix().send(player);
    }

    // ── Create ──────────────────────────────────────────────────────────────────

    private void onCreate(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var name = ctx.require("name", String.class);
        var environment = ctx.get(KEY_ENVIRONMENT, World.Environment.class).orElse(World.Environment.NORMAL);
        var worldType = ctx.get("type", MVWorldType.class).orElse(MVWorldType.DEFAULT);
        var plotSize = ctx.get("plot_size", Long.class).map(Long::intValue).orElse(null);
        var roadWidth = ctx.get("road_width", Long.class).map(Long::intValue).orElse(null);
        var schematic = ctx.get(KEY_SCHEMATIC, String.class).orElse(null);

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
        var world = ctx.require(KEY_WORLD, MVWorld.class);

        r18n().msg("multiverse.deleting_world").prefix()
                .with(KEY_WORLD_NAME, world.getIdentifier())
                .send(sender);

        service.deleteWorld(world.getIdentifier()).thenAccept(success -> {
            PlatformScheduler.of(plugin).runSync(() -> {
                if (Boolean.TRUE.equals(success)) {
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

        var world = ctx.require(KEY_WORLD, MVWorld.class);

        viewFrame.open(MultiverseEditorView.class, player, Map.of(
                "plugin", plugin,
                KEY_WORLD, world,
                "service", service
        ));
    }

    // ── Teleport ────────────────────────────────────────────────────────────────

    private void onTeleport(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElse(null);
        if (player == null) return;

        var world = ctx.require(KEY_WORLD, MVWorld.class);
        var bukkitWorld = worldFactory.getBukkitWorld(world.getIdentifier());

        if (bukkitWorld.isEmpty()) {
            r18n().msg("multiverse.world_not_loaded").prefix()
                    .with(KEY_WORLD_NAME, world.getIdentifier())
                    .send(player);
            return;
        }

        // The stored spawn may carry a null world (deserialized before the world
        // loaded); rebind it to the now-loaded world before teleporting.
        final var target = liveSpawn(world.getSpawnLocation(), bukkitWorld.get());

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

    /**
     * Resolves a teleportable spawn: the stored spawn rebound to {@code world}
     * (its deserialized world reference may be {@code null} if the world wasn't
     * loaded yet), or the world's own spawn when none is stored.
     */
    private static @NotNull Location liveSpawn(@Nullable Location stored, @NotNull World world) {
        if (stored == null) {
            return world.getSpawnLocation();
        }
        final Location loc = stored.clone();
        if (loc.getWorld() == null) {
            loc.setWorld(world);
        }
        return loc;
    }

    // ── Load ────────────────────────────────────────────────────────────────────

    private void onLoad(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var world = ctx.require(KEY_WORLD, MVWorld.class);

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
        var world = ctx.require(KEY_WORLD, MVWorld.class);

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
        var loaded = schematicService.load(name).orElse(null);
        if (loaded == null) {
            r18n().msg("multiverse.applyschematic_not_found").prefix()
                    .with(KEY_SCHEMATIC, name)
                    .send(sender);
            return;
        }

        if (world.getType() == MVWorldType.PLOT) {
            // PLOT worlds: tile the schematic across the plot grid in loaded chunks.
            int plotSize = worldFactory.effectivePlotSize(world);
            int roadWidth = worldFactory.effectiveRoadWidth(world);
            int plotHeight = worldFactory.plotConfig().plotHeight();

            var chunks = bukkit.getLoadedChunks();
            int placed = placeSchematicsInChunks(chunks, schematicService, name, bukkit,
                    plotSize, roadWidth, plotHeight);

            r18n().msg("multiverse.applyschematic_done").prefix()
                    .with(KEY_WORLD_NAME, world.getIdentifier())
                    .with(KEY_SCHEMATIC, name)
                    .with(KEY_COUNT, String.valueOf(placed))
                    .with("chunks", String.valueOf(chunks.length))
                    .send(sender);
        } else {
            // Non-PLOT worlds (VOID / NORMAL hub builds): single paste at the
            // world spawn, mirroring the create-time non-plot behaviour.
            var spawn = bukkit.getSpawnLocation();
            loaded.place(bukkit, spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
            r18n().msg("multiverse.applyschematic_done_single").prefix()
                    .with(KEY_WORLD_NAME, world.getIdentifier())
                    .with(KEY_SCHEMATIC, name)
                    .with("x", String.valueOf(spawn.getBlockX()))
                    .with("y", String.valueOf(spawn.getBlockY()))
                    .with("z", String.valueOf(spawn.getBlockZ()))
                    .send(sender);
        }
    }

    private int placeSchematicsInChunks(org.bukkit.Chunk @NotNull [] chunks,
                                        @NotNull SchematicService schematicService,
                                        @NotNull String name, @NotNull World bukkit,
                                        int plotSize, int roadWidth, int plotHeight) {
        int interval = plotSize + roadWidth;
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
                                schematicService, name, bukkit,
                                new PlacementParams(gridX, gridZ, plotSize, roadWidth, plotHeight),
                                RANDOM);
                        placed++;
                    }
                }
            }
        }
        return placed;
    }

    // ── Paste schematic (anywhere) ───────────────────────────────────────────────

    private void onPaste(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        if (!(sender instanceof Player player)) {
            r18n().msg("multiverse.paste_player_only").prefix().send(sender);
            return;
        }
        var name = ctx.get(KEY_SCHEMATIC, String.class).orElse(null);
        if (name == null) {
            onPasteClipboard(player);
            return;
        }
        var placed = worldFactory.schematics().load(name).orElse(null);
        if (placed == null) {
            r18n().msg("multiverse.applyschematic_not_found").prefix()
                    .with(KEY_SCHEMATIC, name)
                    .send(player);
            return;
        }
        // Paste directly where the player stands: centered horizontally on them,
        // bottom layer at their feet. Works in any world (no plot-world needed).
        var world = player.getWorld();
        var loc = player.getLocation().getBlock().getLocation();
        var size = placed.size();
        int originX = loc.getBlockX() - size.getBlockX() / 2;
        int originY = loc.getBlockY();
        int originZ = loc.getBlockZ() - size.getBlockZ() / 2;
        placed.place(world, originX, originY, originZ);

        r18n().msg("multiverse.paste_done").prefix()
                .with(KEY_SCHEMATIC, name)
                .with(KEY_WORLD_NAME, world.getName())
                .with("x", String.valueOf(loc.getBlockX()))
                .with("y", String.valueOf(loc.getBlockY()))
                .with("z", String.valueOf(loc.getBlockZ()))
                .with("width", String.valueOf(size.getBlockX()))
                .with("height", String.valueOf(size.getBlockY()))
                .with("length", String.valueOf(size.getBlockZ()))
                .send(player);

        boolean setSpawn = ctx.get("setspawn", String.class)
                .map(s -> s.equalsIgnoreCase("setspawn")).orElse(false);
        if (setSpawn) {
            service.setSpawn(world.getName(), loc);
            r18n().msg("multiverse.paste_spawn_set").prefix()
                    .with(KEY_WORLD_NAME, world.getName())
                    .send(player);
        }
    }

    // ── WorldEdit-free region toolkit ────────────────────────────────────────────

    private void onWand(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        player.getInventory().addItem(selections.wandItem());
        r18n().msg("multiverse.edit.wand_given").prefix().send(player);
    }

    private void onPos1(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        var set = selections.setPos1(player, player.getLocation());
        sendCorner(player, "multiverse.edit.pos1_set", set);
    }

    private void onPos2(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        var set = selections.setPos2(player, player.getLocation());
        sendCorner(player, "multiverse.edit.pos2_set", set);
    }

    /** Fills the current selection with a single block ({@code /mv set <block>}). */
    private void onSet(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        var selection = selectionOrWarn(player);
        if (selection == null) {
            return;
        }
        String raw = ctx.require("block", String.class);
        Material material = Material.matchMaterial(raw);
        if (material == null || !material.isBlock()) {
            r18n().msg("multiverse.edit.bad_material").prefix().with("material", raw).send(player);
            return;
        }
        long count = selection.blockCount();
        r18n().msg(MSG_EDIT_WORKING).prefix()
                .with(KEY_COUNT, String.valueOf(count)).send(player);
        editor.fill(player.getUniqueId(), selection, material.createBlockData()).thenRun(() ->
                r18n().msg("multiverse.edit.set_done").prefix()
                        .with(KEY_COUNT, String.valueOf(count))
                        .with("material", material.name()).send(player));
    }

    /** Toggles the live particle outline of the player's pos1↔pos2 selection. */
    private void onSelectionToggle(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        if (selectionBorder.isShowing(player.getUniqueId())) {
            selectionBorder.toggle(player);
            r18n().msg("multiverse.edit.selection_hidden").prefix().send(player);
            return;
        }
        if (selectionOrWarn(player) == null) {
            return;
        }
        selectionBorder.toggle(player);
        r18n().msg("multiverse.edit.selection_shown").prefix().send(player);
    }

    private void onCopy(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        var selection = selectionOrWarn(player);
        if (selection == null) {
            return;
        }
        r18n().msg(MSG_EDIT_WORKING).prefix()
                .with(KEY_COUNT, String.valueOf(selection.blockCount())).send(player);
        editor.copy(player.getUniqueId(), selection, feetAnchor(player)).thenAccept(count ->
                r18n().msg("multiverse.edit.copy_done").prefix()
                        .with(KEY_COUNT, String.valueOf(count)).send(player));
    }

    private void onCut(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        var selection = selectionOrWarn(player);
        if (selection == null) {
            return;
        }
        r18n().msg(MSG_EDIT_WORKING).prefix()
                .with(KEY_COUNT, String.valueOf(selection.blockCount())).send(player);
        editor.cut(player.getUniqueId(), selection, feetAnchor(player)).thenAccept(count ->
                r18n().msg("multiverse.edit.cut_done").prefix()
                        .with(KEY_COUNT, String.valueOf(count)).send(player));
    }

    /** The player's feet block as the copy/cut anchor for relative paste. */
    private static @NotNull BlockVector feetAnchor(@NotNull Player player) {
        Location loc = player.getLocation();
        return new BlockVector(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private void onPasteClipboard(@NotNull Player player) {
        if (editor.clipboard(player.getUniqueId()).isEmpty()) {
            r18n().msg(MSG_EDIT_NO_CLIPBOARD).prefix().send(player);
            return;
        }
        editor.paste(player, true, false).thenAccept(result -> result.ifPresent(r ->
                r18n().msg("multiverse.edit.paste_done").prefix()
                        .with("x", String.valueOf(r.x()))
                        .with("y", String.valueOf(r.y()))
                        .with("z", String.valueOf(r.z()))
                        .with("width", String.valueOf(r.width()))
                        .with("height", String.valueOf(r.height()))
                        .with("length", String.valueOf(r.length()))
                        .send(player)));
    }

    private void onSave(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        var selection = selectionOrWarn(player);
        if (selection == null) {
            return;
        }
        var name = ctx.require("name", String.class);
        try {
            long count = editor.save(selection, name);
            worldFactory.schematics().invalidate(name);
            r18n().msg("multiverse.edit.save_done").prefix()
                    .with(KEY_SCHEMATIC, name)
                    .with(KEY_COUNT, String.valueOf(count)).send(player);
        } catch (IllegalStateException e) {
            r18n().msg("multiverse.edit.save_failed").prefix()
                    .with(KEY_SCHEMATIC, name).send(player);
        }
    }

    private void onRotate(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        int turns = switch (ctx.require("degrees", String.class).trim()) {
            case "90" -> 1;
            case "180" -> 2;
            case "270" -> 3;
            default -> -1;
        };
        if (turns < 0) {
            r18n().msg("multiverse.edit.rotate_bad_arg").prefix().send(player);
            return;
        }
        if (!editor.rotate(player.getUniqueId(), turns)) {
            r18n().msg(MSG_EDIT_NO_CLIPBOARD).prefix().send(player);
            return;
        }
        r18n().msg("multiverse.edit.rotate_done").prefix()
                .with("degrees", String.valueOf(turns * 90)).send(player);
    }

    private void onFlip(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        String axis = ctx.get("axis", String.class).orElse("x").trim().toLowerCase(java.util.Locale.ROOT);
        boolean flipX = !axis.startsWith("z") && !axis.startsWith("n");
        if (!editor.flip(player.getUniqueId(), flipX)) {
            r18n().msg(MSG_EDIT_NO_CLIPBOARD).prefix().send(player);
            return;
        }
        r18n().msg("multiverse.edit.flip_done").prefix()
                .with("axis", flipX ? "X" : "Z").send(player);
    }

    private void onUndo(@NotNull CommandContext ctx) {
        if (!(ctx.sender() instanceof Player player)) {
            return;
        }
        editor.undo(player.getUniqueId()).thenAccept(restored -> {
            if (Boolean.TRUE.equals(restored)) {
                r18n().msg("multiverse.edit.undo_done").prefix().send(player);
            } else {
                r18n().msg("multiverse.edit.undo_empty").prefix().send(player);
            }
        });
    }

    private @Nullable Selection selectionOrWarn(@NotNull Player player) {
        var selection = selections.selection(player.getUniqueId()).orElse(null);
        if (selection == null) {
            r18n().msg("multiverse.edit.no_selection").prefix().send(player);
            return null;
        }
        if (!selection.world().equals(player.getWorld())) {
            r18n().msg("multiverse.edit.wrong_world").prefix().send(player);
            return null;
        }
        return selection;
    }

    private void sendCorner(@NotNull Player player, @NotNull String key, @NotNull Location at) {
        r18n().msg(key).prefix()
                .with("x", String.valueOf(at.getBlockX()))
                .with("y", String.valueOf(at.getBlockY()))
                .with("z", String.valueOf(at.getBlockZ()))
                .with(KEY_COUNT, selections.selection(player.getUniqueId())
                        .map(s -> String.valueOf(s.blockCount())).orElse("—"))
                .send(player);
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
