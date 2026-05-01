package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.view.BaseView;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.service.MultiverseService;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Compact 3-row editor for a managed world.
 *
 * <p>Layout:
 * <pre>
 *   . . S . G . P . .
 *   . . T . W . D . .
 *   . . . . V . . . .
 * </pre>
 *
 * <ul>
 *   <li>S — set spawn to player's location (only when player is in this world)</li>
 *   <li>G — toggle global spawn (auto-disables it on any other world)</li>
 *   <li>P — toggle PvP (persisted on save)</li>
 *   <li>T — cycle time of day (live world; not persisted)</li>
 *   <li>W — cycle weather: clear → rain → storm (live world; not persisted)</li>
 *   <li>D — cycle difficulty: peaceful → easy → normal → hard (live world; not persisted)</li>
 *   <li>V — save persisted fields to database and close</li>
 * </ul>
 *
 * <p>Clicks are explicitly cancelled and slot icons/lore are re-rendered in
 * place via the IF {@code ViewContainer.renderItem(slot, item)} API so toggles
 * reflect the new state immediately without re-opening.
 *
 * <p>Required initial-data keys: {@code "plugin"}, {@code "world"}, {@code "service"}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class MultiverseEditorView extends BaseView {

    private static final String DATA_PLUGIN  = "plugin";
    private static final String DATA_WORLD   = "world";
    private static final String DATA_SERVICE = "service";

    private final State<JavaPlugin>        pluginState  = initialState(DATA_PLUGIN);
    private final State<MVWorld>           worldState   = initialState(DATA_WORLD);
    private final State<MultiverseService> serviceState = initialState(DATA_SERVICE);

    public MultiverseEditorView() {
        super();
    }

    @Override
    protected String translationKey() {
        return "multiverse_editor_ui";
    }

    @Override
    protected String[] layout() {
        return new String[]{
                "  S G P  ",
                "  T W D  ",
                "    V    "
        };
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull me.devnatan.inventoryframework.context.OpenContext open) {
        return Map.of("world_name", worldState.get(open).getIdentifier());
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        var world   = worldState.get(render);
        var service = serviceState.get(render);

        render.layoutSlot('S', spawnItem(player, world)).onClick(c -> handleSpawn(c, world));
        render.layoutSlot('G', globalItem(player, world)).onClick(c -> handleGlobal(c, world, service));
        render.layoutSlot('P', pvpItem(player, world)).onClick(c -> handlePvp(c, world));
        render.layoutSlot('T', timeItem(player, world)).onClick(c -> handleTime(c, world, player));
        render.layoutSlot('W', weatherItem(player, world)).onClick(c -> handleWeather(c, world, player));
        render.layoutSlot('D', difficultyItem(player, world)).onClick(c -> handleDifficulty(c, world, player));
        render.layoutSlot('V', saveItem(player, world)).onClick(c -> handleSave(c, world, service));
    }

    // ── Item builders ───────────────────────────────────────────────────────────

    private ItemStack spawnItem(Player player, MVWorld world) {
        var spawn = world.getFormattedSpawnLocation();
        return createItem(
                Material.COMPASS,
                i18n("spawn.name", player).withPlaceholder("value", spawn).build().component(),
                i18n("spawn.lore", player).withPlaceholder("value", spawn).build().children()
        );
    }

    private ItemStack globalItem(Player player, MVWorld world) {
        var on = world.isGlobalizedSpawn();
        return createItem(
                on ? Material.NETHER_STAR : Material.ENDER_PEARL,
                i18n("global_spawn.name", player).withPlaceholder("value", on ? "enabled" : "disabled").build().component(),
                i18n("global_spawn.lore", player).withPlaceholder("value", on ? "enabled" : "disabled").build().children()
        );
    }

    private ItemStack pvpItem(Player player, MVWorld world) {
        var on = world.isPvpEnabled();
        return createItem(
                on ? Material.DIAMOND_SWORD : Material.WOODEN_SWORD,
                i18n("pvp.name", player).withPlaceholder("value", on ? "enabled" : "disabled").build().component(),
                i18n("pvp.lore", player).withPlaceholder("value", on ? "enabled" : "disabled").build().children()
        );
    }

    private ItemStack timeItem(Player player, MVWorld world) {
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        var phase = bukkit == null ? "—" : timePhase(bukkit.getTime());
        return createItem(
                Material.CLOCK,
                i18n("time.name", player).withPlaceholder("value", phase).build().component(),
                i18n("time.lore", player).withPlaceholder("value", phase).build().children()
        );
    }

    private ItemStack weatherItem(Player player, MVWorld world) {
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        var phase = bukkit == null ? "—" : weatherPhase(bukkit);
        var icon = bukkit != null && bukkit.hasStorm()
                ? (bukkit.isThundering() ? Material.LIGHTNING_ROD : Material.WATER_BUCKET)
                : Material.SUNFLOWER;
        return createItem(
                icon,
                i18n("weather.name", player).withPlaceholder("value", phase).build().component(),
                i18n("weather.lore", player).withPlaceholder("value", phase).build().children()
        );
    }

    private ItemStack difficultyItem(Player player, MVWorld world) {
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        var diff = bukkit == null ? "—" : bukkit.getDifficulty().name().toLowerCase();
        return createItem(
                Material.CREEPER_HEAD,
                i18n("difficulty.name", player).withPlaceholder("value", diff).build().component(),
                i18n("difficulty.lore", player).withPlaceholder("value", diff).build().children()
        );
    }

    private ItemStack saveItem(Player player, MVWorld world) {
        return createItem(
                Material.EMERALD,
                i18n("save.name", player).build().component(),
                i18n("save.lore", player).withPlaceholder("world_name", world.getIdentifier()).build().children()
        );
    }

    // ── Click handlers ──────────────────────────────────────────────────────────

    private void handleSpawn(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        if (!p.getWorld().getName().equals(world.getIdentifier())) {
            R18nManager.getInstance()
                    .msg("multiverse_editor_ui.spawn.wrong_world").prefix()
                    .with("world_name", world.getIdentifier())
                    .send(p);
            return;
        }
        world.setSpawnLocation(p.getLocation());
        R18nManager.getInstance()
                .msg("multiverse_editor_ui.spawn.updated").prefix()
                .with("world_name", world.getIdentifier())
                .send(p);
        refreshSlot(click, spawnItem(p, world));
    }

    private void handleGlobal(SlotClickContext click, MVWorld world, MultiverseService service) {
        click.setCancelled(true);
        var p = click.getPlayer();

        if (world.isGlobalizedSpawn()) {
            world.setGlobalizedSpawn(false);
            service.updateWorld(world);
            R18nManager.getInstance()
                    .msg("multiverse_editor_ui.global_spawn.toggled").prefix()
                    .with("world_name", world.getIdentifier())
                    .with("value", "disabled")
                    .send(p);
            refreshSlot(click, globalItem(p, world));
            return;
        }

        var previous = service.getAllWorldEntities().stream()
                .filter(MVWorld::isGlobalizedSpawn)
                .filter(other -> !other.getIdentifier().equals(world.getIdentifier()))
                .findFirst();

        service.setGlobalSpawn(world.getIdentifier()).thenAccept(success -> {
            if (success) {
                var r18n = R18nManager.getInstance();
                previous.ifPresentOrElse(
                        prev -> r18n.msg("multiverse_editor_ui.global_spawn.replaced").prefix()
                                .with("world_name", world.getIdentifier())
                                .with("previous", prev.getIdentifier())
                                .send(p),
                        () -> r18n.msg("multiverse_editor_ui.global_spawn.toggled").prefix()
                                .with("world_name", world.getIdentifier())
                                .with("value", "enabled")
                                .send(p)
                );
            }
            Bukkit.getScheduler().runTask(pluginState.get(click), () -> refreshSlot(click, globalItem(p, world)));
        });
    }

    private void handlePvp(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        world.setPvpEnabled(!world.isPvpEnabled());
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        if (bukkit != null) bukkit.setPVP(world.isPvpEnabled());
        R18nManager.getInstance()
                .msg("multiverse_editor_ui.pvp.toggled").prefix()
                .with("world_name", world.getIdentifier())
                .with("value", world.isPvpEnabled() ? "enabled" : "disabled")
                .send(p);
        refreshSlot(click, pvpItem(p, world));
    }

    private void handleTime(SlotClickContext click, MVWorld world, Player viewer) {
        click.setCancelled(true);
        var p = click.getPlayer();
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        if (bukkit == null) return;
        // Cycle: day → noon → night → midnight → day
        var current = bukkit.getTime();
        long next;
        if      (current < 6000)  next = 6000L;     // → noon
        else if (current < 13000) next = 13000L;    // → night
        else if (current < 18000) next = 18000L;    // → midnight
        else                      next = 1000L;     // → day
        bukkit.setTime(next);
        R18nManager.getInstance()
                .msg("multiverse_editor_ui.time.updated").prefix()
                .with("world_name", world.getIdentifier())
                .with("value", timePhase(next))
                .send(p);
        refreshSlot(click, timeItem(viewer, world));
    }

    private void handleWeather(SlotClickContext click, MVWorld world, Player viewer) {
        click.setCancelled(true);
        var p = click.getPlayer();
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        if (bukkit == null) return;
        // Cycle: clear → rain → storm → clear
        if (!bukkit.hasStorm()) {
            bukkit.setStorm(true);
            bukkit.setThundering(false);
        } else if (!bukkit.isThundering()) {
            bukkit.setThundering(true);
        } else {
            bukkit.setStorm(false);
            bukkit.setThundering(false);
        }
        R18nManager.getInstance()
                .msg("multiverse_editor_ui.weather.updated").prefix()
                .with("world_name", world.getIdentifier())
                .with("value", weatherPhase(bukkit))
                .send(p);
        refreshSlot(click, weatherItem(viewer, world));
    }

    private void handleDifficulty(SlotClickContext click, MVWorld world, Player viewer) {
        click.setCancelled(true);
        var p = click.getPlayer();
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        if (bukkit == null) return;
        var current = bukkit.getDifficulty();
        var next = switch (current) {
            case PEACEFUL -> Difficulty.EASY;
            case EASY     -> Difficulty.NORMAL;
            case NORMAL   -> Difficulty.HARD;
            case HARD     -> Difficulty.PEACEFUL;
        };
        bukkit.setDifficulty(next);
        R18nManager.getInstance()
                .msg("multiverse_editor_ui.difficulty.updated").prefix()
                .with("world_name", world.getIdentifier())
                .with("value", next.name().toLowerCase())
                .send(p);
        refreshSlot(click, difficultyItem(viewer, world));
    }

    private void handleSave(SlotClickContext click, MVWorld world, MultiverseService service) {
        click.setCancelled(true);
        var p = click.getPlayer();
        var plugin = pluginState.get(click);
        service.updateWorld(world).thenAccept(saved ->
                Bukkit.getScheduler().runTask(plugin, () ->
                        R18nManager.getInstance()
                                .msg("multiverse_editor_ui.save.success").prefix()
                                .with("world_name", saved.getIdentifier())
                                .send(p))
        ).exceptionally(ex -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Failed to save world '" + world.getIdentifier() + "'", ex);
            var rootCause = ex.getCause() != null ? ex.getCause() : ex;
            var msg = rootCause.getClass().getSimpleName()
                    + (rootCause.getMessage() != null ? ": " + rootCause.getMessage() : "");
            Bukkit.getScheduler().runTask(plugin, () ->
                    R18nManager.getInstance()
                            .msg("multiverse_editor_ui.save.failed").prefix()
                            .with("world_name", world.getIdentifier())
                            .with("error", msg)
                            .send(p));
            return null;
        });
        click.closeForPlayer();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static void refreshSlot(SlotClickContext click, ItemStack newItem) {
        click.getClickedContainer().renderItem(click.getClickedSlot(), newItem);
    }

    private static String timePhase(long ticks) {
        if (ticks < 6000)  return "morning";
        if (ticks < 12000) return "noon";
        if (ticks < 18000) return "evening";
        return "night";
    }

    private static String weatherPhase(World w) {
        if (!w.hasStorm()) return "clear";
        if (w.isThundering()) return "storm";
        return "rain";
    }
}
