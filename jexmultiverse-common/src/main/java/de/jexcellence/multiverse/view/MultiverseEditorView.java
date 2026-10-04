package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.service.MultiverseService;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.logging.Level;

/**
 * Editor for a managed world's persisted settings: the world header at slot 4, twelve setting cards in three
 * spaced rows and Save below them. Back returns to the world list.
 *
 * <p>Every card stages its change on the in-memory {@link MVWorld} and previews it on the live world; nothing
 * reaches the database until <em>Save</em>. The one exception is <em>global spawn</em>, which writes
 * immediately because it clears the flag from whichever other world held it.
 *
 * <p>Previewing goes through {@link WorldFactory#applyWorldSettings(World, MVWorld)}, the same method the
 * world loader uses, so the preview and the next restart cannot drift.
 *
 * <p>Required initial-data keys: {@code "plugin"}, {@code "world"}, {@code "service"}, {@code "factory"}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class MultiverseEditorView extends MultiverseBaseView {

    static final String DATA_PLUGIN = "plugin";
    static final String DATA_WORLD = "world";
    static final String DATA_SERVICE = "service";
    static final String DATA_FACTORY = "factory";

    private static final String MSG = "multiverse_editor_ui.";
    private static final String KEY_WORLD_NAME = "world_name";
    private static final String KEY_VALUE = "value";
    private static final String TOGGLED = ".toggled";
    private static final String NOT_LOADED = "not_loaded";
    private static final String ENABLED = "enabled";
    private static final String DISABLED = "disabled";
    private static final int SAVE_SLOT = 40;

    /** Vanilla default time of day used when pinning a world with no live handle. */
    private static final long DEFAULT_PINNED_TIME = 1000L;

    private final State<JavaPlugin> pluginState = initialState(DATA_PLUGIN);
    private final State<MVWorld> worldState = initialState(DATA_WORLD);
    private final State<MultiverseService> serviceState = initialState(DATA_SERVICE);
    private final State<WorldFactory> factoryState = initialState(DATA_FACTORY);

    @Override
    protected String translationKey() {
        return "mv_gui.world_editor";
    }

    @Override
    protected String backDestination() {
        return "world-list";
    }

    @Override
    protected void onBack(@NotNull SlotClickContext click) {
        Map<String, Object> data = copyData(click);
        data.remove(DATA_WORLD);
        click.openForPlayer(MultiverseListView.class, data);
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull OpenContext open) {
        return Map.of(KEY_WORLD_NAME, worldState.get(open).getIdentifier());
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        MVWorld world = worldState.get(render);
        MultiverseService service = serviceState.get(render);
        render.slot(MultiverseLayout.SLOT_HEADER, WorldEditorCards.header(player, world));

        int[] first = MultiverseLayout.spacedRow(4, 1);
        render.slot(first[0], WorldEditorCards.spawn(player, world)).onClick(c -> handleSpawn(c, world));
        render.slot(first[1], WorldEditorCards.globalSpawn(player, world)).onClick(c -> handleGlobal(c, world, service));
        render.slot(first[2], WorldEditorCards.pvp(player, world)).onClick(c -> handlePvp(c, world));
        render.slot(first[3], WorldEditorCards.keepSpawn(player, world)).onClick(c -> handleKeepSpawn(c, world));

        int[] second = MultiverseLayout.spacedRow(4, 2);
        render.slot(second[0], WorldEditorCards.time(player, world)).onClick(c -> handleTime(c, world));
        render.slot(second[1], WorldEditorCards.timeLock(player, world)).onClick(c -> handleTimeLock(c, world));
        render.slot(second[2], WorldEditorCards.weather(player, world)).onClick(c -> handleWeather(c, world));
        render.slot(second[3], WorldEditorCards.weatherLock(player, world)).onClick(c -> handleWeatherLock(c, world));

        int[] third = MultiverseLayout.spacedRow(4, 3);
        render.slot(third[0], WorldEditorCards.difficulty(player, world)).onClick(c -> handleDifficulty(c, world));
        render.slot(third[1], WorldEditorCards.buildLock(player, world)).onClick(c -> handleBuildLock(c, world));
        render.slot(third[2], WorldEditorCards.interactions(player, world)).onClick(c -> handleInteractions(c, world));
        render.slot(third[3], WorldEditorCards.gameRules(player, world))
                .onClick(click -> click.openForPlayer(MultiverseGameRulesView.class, copyData(click)));

        render.slot(SAVE_SLOT, WorldEditorCards.save(player)).onClick(c -> handleSave(c, world, service));
    }

    private void handleSpawn(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        Player p = click.getPlayer();
        if (!p.getWorld().getName().equals(world.getIdentifier())) {
            notify(p, "spawn.wrong_world", world, null);
            return;
        }
        world.setSpawnLocation(p.getLocation());
        notify(p, "spawn.updated", world, null);
        refresh(click, world, WorldEditorCards::spawn);
    }

    private void handleGlobal(@NotNull SlotClickContext click, @NotNull MVWorld world,
                              @NotNull MultiverseService service) {
        Player p = click.getPlayer();
        if (world.isGlobalizedSpawn()) {
            world.setGlobalizedSpawn(false);
            service.updateWorld(world);
            notify(p, "global_spawn" + TOGGLED, world, DISABLED);
            refresh(click, world, WorldEditorCards::globalSpawn);
            return;
        }
        var previous = service.getAllWorldEntities().stream()
                .filter(MVWorld::isGlobalizedSpawn)
                .filter(other -> !other.getIdentifier().equals(world.getIdentifier()))
                .findFirst();
        service.setGlobalSpawn(world.getIdentifier()).thenAccept(success ->
                PlatformScheduler.of(pluginState.get(click)).runSync(() -> {
                    if (Boolean.TRUE.equals(success)) {
                        world.setGlobalizedSpawn(true);
                        previous.ifPresentOrElse(
                                prev -> MultiverseCards.msg(MSG + "global_spawn.replaced").prefix()
                                        .with(KEY_WORLD_NAME, world.getIdentifier())
                                        .with("previous", prev.getIdentifier())
                                        .send(p),
                                () -> notify(p, "global_spawn" + TOGGLED, world, ENABLED));
                    }
                    refresh(click, world, WorldEditorCards::globalSpawn);
                }));
    }

    private void handlePvp(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        world.setPvpEnabled(!world.isPvpEnabled());
        World live = Bukkit.getWorld(world.getIdentifier());
        if (live != null) {
            live.setPVP(world.isPvpEnabled());
        }
        notify(click.getPlayer(), "pvp" + TOGGLED, world, stateWord(world.isPvpEnabled()));
        refresh(click, world, WorldEditorCards::pvp);
    }

    private void handleKeepSpawn(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        world.setKeepSpawnLoaded(!world.isKeepSpawnLoaded());
        preview(click, world);
        notify(click.getPlayer(), "keep_spawn" + TOGGLED, world, stateWord(world.isKeepSpawnLoaded()));
        refresh(click, world, WorldEditorCards::keepSpawn);
    }

    private void handleTime(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        World live = Bukkit.getWorld(world.getIdentifier());
        if (live == null) {
            notify(click.getPlayer(), NOT_LOADED, world, null);
            return;
        }
        long next = nextTime(live.getTime());
        live.setTime(next);
        if (world.getFixedTime() != null) {
            world.setFixedTime(next);
        }
        notify(click.getPlayer(), "time.updated", world, WorldEditorCards.timePhase(next));
        refresh(click, world, WorldEditorCards::time);
    }

    private void handleTimeLock(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        if (world.getFixedTime() != null) {
            world.setFixedTime(null);
            notify(click.getPlayer(), "time_lock" + TOGGLED, world, DISABLED);
        } else {
            World live = Bukkit.getWorld(world.getIdentifier());
            long pinned = live == null ? DEFAULT_PINNED_TIME : live.getTime();
            world.setFixedTime(pinned);
            notify(click.getPlayer(), "time_lock" + TOGGLED, world, WorldEditorCards.timePhase(pinned));
        }
        preview(click, world);
        refresh(click, world, WorldEditorCards::timeLock);
    }

    private void handleWeather(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        World live = Bukkit.getWorld(world.getIdentifier());
        if (live == null) {
            notify(click.getPlayer(), NOT_LOADED, world, null);
            return;
        }
        cycleWeather(live);
        if (world.isWeatherLocked()) {
            world.setWeatherType(WorldEditorCards.weatherPhase(live).toUpperCase(Locale.ROOT));
        }
        notify(click.getPlayer(), "weather.updated", world, WorldEditorCards.weatherPhase(live));
        refresh(click, world, WorldEditorCards::weather);
    }

    private void handleWeatherLock(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        if (world.isWeatherLocked()) {
            world.setWeatherLocked(false);
            world.setWeatherType(null);
            notify(click.getPlayer(), "weather_lock" + TOGGLED, world, DISABLED);
        } else {
            World live = Bukkit.getWorld(world.getIdentifier());
            world.setWeatherLocked(true);
            world.setWeatherType(live == null ? "CLEAR" : WorldEditorCards.weatherPhase(live).toUpperCase(Locale.ROOT));
            notify(click.getPlayer(), "weather_lock" + TOGGLED, world,
                    WorldEditorCards.weatherWord(world.getWeatherType()));
        }
        preview(click, world);
        refresh(click, world, WorldEditorCards::weatherLock);
    }

    private void handleDifficulty(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        String next = WorldEditorCards.nextDifficulty(world.getDifficulty());
        world.setDifficulty(next);
        preview(click, world);
        notify(click.getPlayer(), "difficulty.updated", world,
                next == null ? "unmanaged" : next.toLowerCase(Locale.ROOT));
        refresh(click, world, WorldEditorCards::difficulty);
    }

    private void handleBuildLock(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        world.setBuildLocked(!world.isBuildLocked());
        notify(click.getPlayer(), "build_lock" + TOGGLED, world, stateWord(world.isBuildLocked()));
        refresh(click, world, WorldEditorCards::buildLock);
    }

    private void handleInteractions(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        var mode = world.getBuildLockInteractionMode().next();
        world.setBuildLockInteractionMode(mode);
        notify(click.getPlayer(), "interactions" + TOGGLED, world, WorldEditorCards.interactionWord(mode));
        refresh(click, world, WorldEditorCards::interactions);
    }

    private void handleSave(@NotNull SlotClickContext click, @NotNull MVWorld world,
                            @NotNull MultiverseService service) {
        Player p = click.getPlayer();
        JavaPlugin plugin = pluginState.get(click);
        service.updateWorld(world).thenAccept(saved ->
                PlatformScheduler.of(plugin).runSync(() ->
                        MultiverseCards.msg(MSG + "save.success").prefix()
                                .with(KEY_WORLD_NAME, saved.getIdentifier())
                                .send(p))
        ).exceptionally(ex -> {
            String worldIdentifier = world.getIdentifier();
            plugin.getLogger().log(Level.SEVERE, ex, () -> "Failed to save world '" + worldIdentifier + "'");
            Throwable rootCause = ex.getCause() != null ? ex.getCause() : ex;
            String detail = rootCause.getMessage() != null ? ": " + rootCause.getMessage() : "";
            String error = rootCause.getClass().getSimpleName() + detail;
            PlatformScheduler.of(plugin).runSync(() ->
                    MultiverseCards.msg(MSG + "save.failed").prefix()
                            .with(KEY_WORLD_NAME, worldIdentifier)
                            .with("error", error)
                            .send(p));
            return null;
        });
        click.closeForPlayer();
    }

    private void preview(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        World live = Bukkit.getWorld(world.getIdentifier());
        if (live != null) {
            factoryState.get(click).applyWorldSettings(live, world);
        }
    }

    private static void refresh(@NotNull SlotClickContext click, @NotNull MVWorld world,
                                @NotNull BiFunction<Player, MVWorld, ItemStack> card) {
        click.getClickedContainer().renderItem(click.getClickedSlot(), card.apply(click.getPlayer(), world));
    }

    private static void notify(@NotNull Player player, @NotNull String key, @NotNull MVWorld world,
                               @Nullable String word) {
        var builder = MultiverseCards.msg(MSG + key).prefix().with(KEY_WORLD_NAME, world.getIdentifier());
        if (word != null) {
            builder.with(KEY_VALUE, WorldEditorCards.plainWord(player, word));
        }
        builder.send(player);
    }

    private static @NotNull String stateWord(boolean enabled) {
        return enabled ? ENABLED : DISABLED;
    }

    private static long nextTime(long current) {
        if (current < 6000) {
            return 6000L;
        }
        if (current < 13000) {
            return 13000L;
        }
        if (current < 18000) {
            return 18000L;
        }
        return DEFAULT_PINNED_TIME;
    }

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
}
