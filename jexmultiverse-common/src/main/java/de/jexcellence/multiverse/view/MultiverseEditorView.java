package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jexplatform.view.BaseView;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.api.BuildLockInteractionMode;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.service.MultiverseService;
import me.devnatan.inventoryframework.context.OpenContext;
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
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Editor for a managed world's persisted settings.
 *
 * <p>Layout:
 * <pre>
 *       I
 *   S G P K
 *   T C W E
 *   D B L R
 *       V
 * </pre>
 *
 * <ul>
 *   <li>I - read-only summary of the world and which settings it manages</li>
 *   <li>S - set spawn to the viewer's location (only while standing in this world)</li>
 *   <li>G - toggle global spawn</li>
 *   <li>P - toggle PvP</li>
 *   <li>K - toggle whether spawn chunks stay loaded</li>
 *   <li>T - cycle time of day</li>
 *   <li>C - pin or release the time of day</li>
 *   <li>W - cycle weather</li>
 *   <li>E - pin or release the weather</li>
 *   <li>D - cycle difficulty, including an unmanaged state</li>
 *   <li>B - toggle build lock</li>
 *   <li>L - cycle the build-lock interaction profile</li>
 *   <li>R - open the per-world gamerule editor</li>
 *   <li>V - save to the database and close</li>
 * </ul>
 *
 * <h2>Save semantics</h2>
 *
 * <p>Every button stages its change on the in-memory {@link MVWorld} and previews it
 * on the live world; nothing reaches the database until <em>Save</em>. The one
 * exception is <em>global spawn</em>, which writes immediately because it clears the
 * flag from whichever other world held it - a cross-world mutation that cannot be
 * meaningfully staged.
 *
 * <p>Previewing goes through {@link WorldFactory#applyWorldSettings(World, MVWorld)},
 * the same method the world loader uses. That is deliberate: what the editor shows is
 * produced by the code that will run on the next restart, so the two cannot drift.
 *
 * <p>Required initial-data keys: {@code "plugin"}, {@code "world"}, {@code "service"},
 * {@code "factory"}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class MultiverseEditorView extends BaseView {

    static final String DATA_PLUGIN  = "plugin";
    static final String DATA_WORLD   = "world";
    static final String DATA_SERVICE = "service";
    static final String DATA_FACTORY = "factory";

    private static final String KEY_WORLD_NAME = "world_name";
    private static final String KEY_VALUE      = "value";
    private static final String VAL_ENABLED    = "enabled";
    private static final String VAL_DISABLED   = "disabled";
    private static final String VAL_UNMANAGED  = "unmanaged";

    /** Vanilla default time of day used when pinning a world with no live handle. */
    private static final long DEFAULT_PINNED_TIME = 1000L;

    private final State<JavaPlugin>        pluginState  = initialState(DATA_PLUGIN);
    private final State<MVWorld>           worldState   = initialState(DATA_WORLD);
    private final State<MultiverseService> serviceState = initialState(DATA_SERVICE);
    private final State<WorldFactory>      factoryState = initialState(DATA_FACTORY);

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
                "    I    ",
                " S G P K ",
                " T C W E ",
                " D B L R ",
                "    V    "
        };
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull OpenContext open) {
        return Map.of(KEY_WORLD_NAME, worldState.get(open).getIdentifier());
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        var world   = worldState.get(render);
        var service = serviceState.get(render);

        render.layoutSlot('I', infoItem(player, world));
        render.layoutSlot('S', spawnItem(player, world)).onClick(c -> handleSpawn(c, world));
        render.layoutSlot('G', globalItem(player, world)).onClick(c -> handleGlobal(c, world, service));
        render.layoutSlot('P', pvpItem(player, world)).onClick(c -> handlePvp(c, world));
        render.layoutSlot('K', keepSpawnItem(player, world)).onClick(c -> handleKeepSpawn(c, world));
        render.layoutSlot('T', timeItem(player, world)).onClick(c -> handleTime(c, world));
        render.layoutSlot('C', timeLockItem(player, world)).onClick(c -> handleTimeLock(c, world));
        render.layoutSlot('W', weatherItem(player, world)).onClick(c -> handleWeather(c, world));
        render.layoutSlot('E', weatherLockItem(player, world)).onClick(c -> handleWeatherLock(c, world));
        render.layoutSlot('D', difficultyItem(player, world)).onClick(c -> handleDifficulty(c, world));
        render.layoutSlot('B', buildLockItem(player, world)).onClick(c -> handleBuildLock(c, world));
        render.layoutSlot('L', interactionItem(player, world)).onClick(c -> handleInteractionMode(c, world));
        render.layoutSlot('R', gameRulesItem(player, world)).onClick(this::handleGameRules);
        render.layoutSlot('V', saveItem(player, world)).onClick(c -> handleSave(c, world, service));
    }

    // ── Item builders ───────────────────────────────────────────────────────────

    private ItemStack infoItem(Player player, MVWorld world) {
        var placeholders = new HashMap<String, Object>();
        placeholders.put(KEY_WORLD_NAME, world.getIdentifier());
        placeholders.put("type", world.getType().name().toLowerCase(Locale.ROOT));
        placeholders.put("environment", world.getEnvironment().name().toLowerCase(Locale.ROOT));
        placeholders.put("game_rules", String.valueOf(world.getGameRules().size()));
        placeholders.put("loaded", Bukkit.getWorld(world.getIdentifier()) != null
                ? VAL_ENABLED : VAL_DISABLED);
        return createItem(
                Material.FILLED_MAP,
                i18n("info.name", player).withPlaceholders(placeholders).build().component(),
                i18n("info.lore", player).withPlaceholders(placeholders).build().children()
        );
    }

    private ItemStack spawnItem(Player player, MVWorld world) {
        var spawn = world.getFormattedSpawnLocation();
        return createItem(
                Material.COMPASS,
                i18n("spawn.name", player).withPlaceholder(KEY_VALUE, spawn).build().component(),
                i18n("spawn.lore", player).withPlaceholder(KEY_VALUE, spawn).build().children()
        );
    }

    private ItemStack globalItem(Player player, MVWorld world) {
        var on = world.isGlobalizedSpawn();
        return toggleItem(player, "global_spawn", on,
                on ? Material.NETHER_STAR : Material.ENDER_PEARL);
    }

    private ItemStack pvpItem(Player player, MVWorld world) {
        var on = world.isPvpEnabled();
        return toggleItem(player, "pvp", on,
                on ? Material.DIAMOND_SWORD : Material.WOODEN_SWORD);
    }

    private ItemStack keepSpawnItem(Player player, MVWorld world) {
        var on = world.isKeepSpawnLoaded();
        return toggleItem(player, "keep_spawn", on,
                on ? Material.BEACON : Material.GLASS);
    }

    private ItemStack timeItem(Player player, MVWorld world) {
        return createItem(
                Material.CLOCK,
                i18n("time.name", player).withPlaceholder(KEY_VALUE, currentTimePhase(world))
                        .build().component(),
                i18n("time.lore", player).withPlaceholder(KEY_VALUE, currentTimePhase(world))
                        .build().children()
        );
    }

    private ItemStack timeLockItem(Player player, MVWorld world) {
        var pinned = world.getFixedTime() != null;
        var value = pinned ? timePhase(world.getFixedTime()) : VAL_DISABLED;
        return createItem(
                pinned ? Material.AMETHYST_SHARD : Material.GLASS_BOTTLE,
                i18n("time_lock.name", player).withPlaceholder(KEY_VALUE, value).build().component(),
                i18n("time_lock.lore", player).withPlaceholder(KEY_VALUE, value).build().children()
        );
    }

    private ItemStack weatherItem(Player player, MVWorld world) {
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        var phase = bukkit == null ? "-" : weatherPhase(bukkit);
        Material stormIcon = bukkit != null && bukkit.isThundering()
                ? Material.LIGHTNING_ROD : Material.WATER_BUCKET;
        var icon = bukkit != null && bukkit.hasStorm() ? stormIcon : Material.SUNFLOWER;
        return createItem(
                icon,
                i18n("weather.name", player).withPlaceholder(KEY_VALUE, phase).build().component(),
                i18n("weather.lore", player).withPlaceholder(KEY_VALUE, phase).build().children()
        );
    }

    private ItemStack weatherLockItem(Player player, MVWorld world) {
        var locked = world.isWeatherLocked();
        var value = locked
                ? String.valueOf(world.getWeatherType()).toLowerCase(Locale.ROOT)
                : VAL_DISABLED;
        return createItem(
                locked ? Material.AMETHYST_SHARD : Material.GLASS_BOTTLE,
                i18n("weather_lock.name", player).withPlaceholder(KEY_VALUE, value).build().component(),
                i18n("weather_lock.lore", player).withPlaceholder(KEY_VALUE, value).build().children()
        );
    }

    private ItemStack difficultyItem(Player player, MVWorld world) {
        var configured = world.getDifficulty();
        var value = configured == null ? VAL_UNMANAGED : configured.toLowerCase(Locale.ROOT);
        return createItem(
                configured == null ? Material.STRUCTURE_VOID : Material.CREEPER_HEAD,
                i18n("difficulty.name", player).withPlaceholder(KEY_VALUE, value).build().component(),
                i18n("difficulty.lore", player).withPlaceholder(KEY_VALUE, value).build().children()
        );
    }

    private ItemStack buildLockItem(Player player, MVWorld world) {
        var on = world.isBuildLocked();
        return toggleItem(player, "build_lock", on,
                on ? Material.IRON_DOOR : Material.OAK_DOOR);
    }

    private ItemStack interactionItem(Player player, MVWorld world) {
        BuildLockInteractionMode mode = world.getBuildLockInteractionMode();
        var value = mode.name().toLowerCase(Locale.ROOT);
        return createItem(
                switch (mode) {
                    case OPEN -> Material.OAK_BUTTON;
                    case SAFE -> Material.LEVER;
                    case LOCKED -> Material.BARRIER;
                },
                i18n("interactions.name", player).withPlaceholder(KEY_VALUE, value).build().component(),
                i18n("interactions.lore", player).withPlaceholder(KEY_VALUE, value).build().children()
        );
    }

    private ItemStack gameRulesItem(Player player, MVWorld world) {
        var count = String.valueOf(world.getGameRules().size());
        return createItem(
                Material.COMMAND_BLOCK,
                i18n("game_rules.name", player).withPlaceholder(KEY_VALUE, count).build().component(),
                i18n("game_rules.lore", player).withPlaceholder(KEY_VALUE, count).build().children()
        );
    }

    private ItemStack saveItem(Player player, MVWorld world) {
        return createItem(
                Material.EMERALD,
                i18n("save.name", player).build().component(),
                i18n("save.lore", player).withPlaceholder(KEY_WORLD_NAME, world.getIdentifier())
                        .build().children()
        );
    }

    /**
     * Builds a standard enabled/disabled toggle icon.
     *
     * @param player   the viewer, for localisation
     * @param keyBase  the translation key prefix
     * @param enabled  the current state
     * @param material the icon to use
     * @return the rendered item
     */
    private ItemStack toggleItem(Player player, String keyBase, boolean enabled, Material material) {
        var value = enabled ? VAL_ENABLED : VAL_DISABLED;
        return createItem(
                material,
                i18n(keyBase + ".name", player).withPlaceholder(KEY_VALUE, value).build().component(),
                i18n(keyBase + ".lore", player).withPlaceholder(KEY_VALUE, value).build().children()
        );
    }

    // ── Click handlers ──────────────────────────────────────────────────────────

    private void handleSpawn(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        if (!p.getWorld().getName().equals(world.getIdentifier())) {
            notify(p, "spawn.wrong_world", world, null);
            return;
        }
        world.setSpawnLocation(p.getLocation());
        notify(p, "spawn.updated", world, null);
        refreshSlot(click, spawnItem(p, world));
    }

    private void handleGlobal(SlotClickContext click, MVWorld world, MultiverseService service) {
        click.setCancelled(true);
        var p = click.getPlayer();

        if (world.isGlobalizedSpawn()) {
            world.setGlobalizedSpawn(false);
            service.updateWorld(world);
            notify(p, "global_spawn.toggled", world, VAL_DISABLED);
            refreshSlot(click, globalItem(p, world));
            return;
        }

        var previous = service.getAllWorldEntities().stream()
                .filter(MVWorld::isGlobalizedSpawn)
                .filter(other -> !other.getIdentifier().equals(world.getIdentifier()))
                .findFirst();

        service.setGlobalSpawn(world.getIdentifier()).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                world.setGlobalizedSpawn(true);
                var r18n = R18nManager.getInstance();
                previous.ifPresentOrElse(
                        prev -> r18n.msg("multiverse_editor_ui.global_spawn.replaced").prefix()
                                .with(KEY_WORLD_NAME, world.getIdentifier())
                                .with("previous", prev.getIdentifier())
                                .send(p),
                        () -> notify(p, "global_spawn.toggled", world, VAL_ENABLED)
                );
            }
            PlatformScheduler.of(pluginState.get(click))
                    .runSync(() -> refreshSlot(click, globalItem(p, world)));
        });
    }

    private void handlePvp(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        world.setPvpEnabled(!world.isPvpEnabled());
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        if (bukkit != null) {
            bukkit.setPVP(world.isPvpEnabled());
        }
        notify(p, "pvp.toggled", world, world.isPvpEnabled() ? VAL_ENABLED : VAL_DISABLED);
        refreshSlot(click, pvpItem(p, world));
    }

    private void handleKeepSpawn(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        world.setKeepSpawnLoaded(!world.isKeepSpawnLoaded());
        preview(click, world);
        notify(p, "keep_spawn.toggled", world, world.isKeepSpawnLoaded() ? VAL_ENABLED : VAL_DISABLED);
        refreshSlot(click, keepSpawnItem(p, world));
    }

    private void handleTime(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        if (bukkit == null) {
            notify(p, "not_loaded", world, null);
            return;
        }
        var next = nextTime(bukkit.getTime());
        bukkit.setTime(next);
        // Keep the pinned value in step with what the admin just set, otherwise the
        // preview and the persisted value would disagree.
        if (world.getFixedTime() != null) {
            world.setFixedTime(next);
        }
        notify(p, "time.updated", world, timePhase(next));
        refreshSlot(click, timeItem(p, world));
        rerender(click);
    }

    private void handleTimeLock(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        if (world.getFixedTime() != null) {
            world.setFixedTime(null);
            notify(p, "time_lock.toggled", world, VAL_DISABLED);
        } else {
            var bukkit = Bukkit.getWorld(world.getIdentifier());
            world.setFixedTime(bukkit == null ? DEFAULT_PINNED_TIME : bukkit.getTime());
            notify(p, "time_lock.toggled", world, timePhase(world.getFixedTime()));
        }
        preview(click, world);
        refreshSlot(click, timeLockItem(p, world));
    }

    private void handleWeather(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        if (bukkit == null) {
            notify(p, "not_loaded", world, null);
            return;
        }
        cycleWeather(bukkit);
        if (world.isWeatherLocked()) {
            world.setWeatherType(weatherPhase(bukkit).toUpperCase(Locale.ROOT));
        }
        notify(p, "weather.updated", world, weatherPhase(bukkit));
        refreshSlot(click, weatherItem(p, world));
        rerender(click);
    }

    private void handleWeatherLock(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        if (world.isWeatherLocked()) {
            world.setWeatherLocked(false);
            world.setWeatherType(null);
            notify(p, "weather_lock.toggled", world, VAL_DISABLED);
        } else {
            var bukkit = Bukkit.getWorld(world.getIdentifier());
            world.setWeatherLocked(true);
            world.setWeatherType(bukkit == null
                    ? "CLEAR" : weatherPhase(bukkit).toUpperCase(Locale.ROOT));
            notify(p, "weather_lock.toggled", world,
                    String.valueOf(world.getWeatherType()).toLowerCase(Locale.ROOT));
        }
        preview(click, world);
        refreshSlot(click, weatherLockItem(p, world));
    }

    private void handleDifficulty(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        var next = nextDifficulty(world.getDifficulty());
        world.setDifficulty(next);
        preview(click, world);
        notify(p, "difficulty.updated", world,
                next == null ? VAL_UNMANAGED : next.toLowerCase(Locale.ROOT));
        refreshSlot(click, difficultyItem(p, world));
    }

    private void handleBuildLock(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        world.setBuildLocked(!world.isBuildLocked());
        notify(p, "build_lock.toggled", world, world.isBuildLocked() ? VAL_ENABLED : VAL_DISABLED);
        refreshSlot(click, buildLockItem(p, world));
    }

    private void handleInteractionMode(SlotClickContext click, MVWorld world) {
        click.setCancelled(true);
        var p = click.getPlayer();
        var mode = world.getBuildLockInteractionMode().next();
        world.setBuildLockInteractionMode(mode);
        notify(p, "interactions.toggled", world, mode.name().toLowerCase(Locale.ROOT));
        refreshSlot(click, interactionItem(p, world));
    }

    private void handleGameRules(SlotClickContext click) {
        click.setCancelled(true);
        click.openForPlayer(MultiverseGameRulesView.class, Map.of(
                DATA_PLUGIN,  pluginState.get(click),
                DATA_WORLD,   worldState.get(click),
                DATA_SERVICE, serviceState.get(click),
                DATA_FACTORY, factoryState.get(click)
        ));
    }

    private void handleSave(SlotClickContext click, MVWorld world, MultiverseService service) {
        click.setCancelled(true);
        var p = click.getPlayer();
        var plugin = pluginState.get(click);
        service.updateWorld(world).thenAccept(saved ->
                PlatformScheduler.of(plugin).runSync(() ->
                        R18nManager.getInstance()
                                .msg("multiverse_editor_ui.save.success").prefix()
                                .with(KEY_WORLD_NAME, saved.getIdentifier())
                                .send(p))
        ).exceptionally(ex -> {
            var worldIdentifier = world.getIdentifier();
            plugin.getLogger().log(Level.SEVERE, ex,
                    () -> "Failed to save world '" + worldIdentifier + "'");
            var rootCause = ex.getCause() != null ? ex.getCause() : ex;
            var msg = rootCause.getClass().getSimpleName()
                    + (rootCause.getMessage() != null ? ": " + rootCause.getMessage() : "");
            PlatformScheduler.of(plugin).runSync(() ->
                    R18nManager.getInstance()
                            .msg("multiverse_editor_ui.save.failed").prefix()
                            .with(KEY_WORLD_NAME, worldIdentifier)
                            .with("error", msg)
                            .send(p));
            return null;
        });
        click.closeForPlayer();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Applies the staged settings to the live world so the admin sees the result
     * immediately.
     *
     * <p>Routes through the loader's own {@code applyWorldSettings}, so the preview
     * and the next restart cannot disagree. No-op when the world is not loaded.
     *
     * @param click the click context, for state access
     * @param world the staged world entity
     */
    private void preview(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        if (bukkit == null) {
            return;
        }
        factoryState.get(click).applyWorldSettings(bukkit, world);
    }

    /**
     * Re-renders the whole menu.
     *
     * <p>Used when one click changes what a <em>different</em> slot should display -
     * cycling the time updates the pin indicator, for instance.
     *
     * @param click the click context
     */
    private static void rerender(@NotNull SlotClickContext click) {
        click.update();
    }

    private void notify(@NotNull Player player, @NotNull String key,
                        @NotNull MVWorld world, @Nullable String value) {
        var builder = R18nManager.getInstance()
                .msg("multiverse_editor_ui." + key).prefix()
                .with(KEY_WORLD_NAME, world.getIdentifier());
        if (value != null) {
            builder.with(KEY_VALUE, value);
        }
        builder.send(player);
    }

    private static void refreshSlot(SlotClickContext click, ItemStack newItem) {
        click.getClickedContainer().renderItem(click.getClickedSlot(), newItem);
    }

    private @NotNull String currentTimePhase(@NotNull MVWorld world) {
        var bukkit = Bukkit.getWorld(world.getIdentifier());
        return bukkit == null ? "-" : timePhase(bukkit.getTime());
    }

    /** Cycles day to noon to night to midnight and back. */
    private static long nextTime(long current) {
        if (current < 6000)  return 6000L;
        if (current < 13000) return 13000L;
        if (current < 18000) return 18000L;
        return DEFAULT_PINNED_TIME;
    }

    /** Cycles clear to rain to storm and back. */
    private static void cycleWeather(@NotNull World world) {
        if (!world.hasStorm()) {
            world.setStorm(true);
            world.setThundering(false);
        } else if (!world.isThundering()) {
            world.setThundering(true);
        } else {
            world.setStorm(false);
            world.setThundering(false);
        }
    }

    /**
     * Cycles peaceful to easy to normal to hard to unmanaged and back.
     *
     * <p>{@code null} is a real state here, not an error: it means the world keeps
     * whatever difficulty the server default gives it.
     *
     * @param current the current stored difficulty name, or {@code null}
     * @return the next difficulty name, or {@code null} for unmanaged
     */
    private static @Nullable String nextDifficulty(@Nullable String current) {
        if (current == null) {
            return Difficulty.PEACEFUL.name();
        }
        return switch (current.toUpperCase(Locale.ROOT)) {
            case "PEACEFUL" -> Difficulty.EASY.name();
            case "EASY"     -> Difficulty.NORMAL.name();
            case "NORMAL"   -> Difficulty.HARD.name();
            default         -> null;
        };
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
