package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.component.FilterHopperButton;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.service.MultiverseService;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every managed world, 28 per page, filterable by loaded state. Left-click teleports to the world's spawn,
 * right-click opens the {@link MultiverseEditorView}. Deleting stays with {@code /mv delete} so a list click
 * can never destroy a world.
 *
 * <p>Required initial-data keys: {@code "plugin"}, {@code "service"}, {@code "factory"}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class MultiverseListView extends MultiverseBaseView {

    static final String KEY = MultiverseCards.ROOT + "world_list.";
    static final List<String> FILTERS = List.of("all", "loaded", "unloaded");
    static final FilterHopperButton FILTER = new FilterHopperButton("jexmultiverse:world-list", FILTERS.size());

    private static final String KEY_WORLD_NAME = "world_name";
    private static final String PLAIN = "plain";

    private final State<JavaPlugin> pluginState = initialState(MultiverseEditorView.DATA_PLUGIN);
    private final State<MultiverseService> serviceState = initialState(MultiverseEditorView.DATA_SERVICE);
    private final State<WorldFactory> factoryState = initialState(MultiverseEditorView.DATA_FACTORY);

    @Override
    protected String translationKey() {
        return "mv_gui.world_list";
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        WorldFactory factory = factoryState.get(render);
        List<MVWorld> all = serviceState.get(render).getAllWorldEntities().stream()
                .sorted(Comparator.comparing(MVWorld::getIdentifier, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int filter = FILTER.index(player.getUniqueId());
        List<MVWorld> shown = all.stream().filter(world -> matches(filter, factory.isWorldLoaded(world.getIdentifier())))
                .toList();

        render.slot(MultiverseLayout.SLOT_HEADER, header(player, all, factory));
        render.slot(MultiverseLayout.SLOT_FILTER, MultiverseCards.filter(player, filterLabels(player), filter))
                .onClick(click -> {
                    FILTER.cycle(click.getPlayer().getUniqueId(), !click.isRightClick());
                    reopen(click, 0);
                });
        if (shown.isEmpty()) {
            render.slot(MultiverseLayout.centreSlot(),
                    MultiverseCards.notice(player, new ItemStack(Material.PAPER), KEY + "empty"));
            return;
        }
        int pages = MultiverseLayout.pageCount(shown.size());
        int page = MultiverseLayout.clampPage(requestedPage(render), pages);
        int from = page * MultiverseLayout.PAGE_SIZE;
        int to = Math.min(shown.size(), from + MultiverseLayout.PAGE_SIZE);
        int[] slots = MultiverseLayout.centred(to - from);
        for (int i = 0; i < slots.length; i++) {
            MVWorld world = shown.get(from + i);
            render.slot(slots[i], worldCard(player, world, factory)).onClick(click -> onWorldClick(click, world));
        }
        pagination(render, player, page, pages);
    }

    private void onWorldClick(@NotNull SlotClickContext click, @NotNull MVWorld world) {
        if (click.isRightClick()) {
            Map<String, Object> data = copyData(click);
            data.remove(DATA_PAGE);
            data.put(MultiverseEditorView.DATA_WORLD, world);
            click.openForPlayer(MultiverseEditorView.class, data);
            return;
        }
        Player player = click.getPlayer();
        World live = factoryState.get(click).getBukkitWorld(world.getIdentifier()).orElse(null);
        if (live == null) {
            MultiverseCards.msg("multiverse.world_not_loaded").prefix()
                    .with(KEY_WORLD_NAME, world.getIdentifier()).send(player);
            return;
        }
        Location stored = world.getSpawnLocation();
        Location spawn = stored != null ? stored.clone() : live.getSpawnLocation();
        if (spawn.getWorld() == null) {
            spawn.setWorld(live);
        }
        click.closeForPlayer();
        PlatformScheduler.of(pluginState.get(click)).runSync(() -> player.teleportAsync(spawn).thenAccept(success -> {
            if (Boolean.TRUE.equals(success)) {
                MultiverseCards.msg("multiverse.teleported").prefix()
                        .with(KEY_WORLD_NAME, world.getIdentifier()).send(player);
            }
        }));
    }

    private static boolean matches(int filter, boolean loaded) {
        return switch (filter) {
            case 1 -> loaded;
            case 2 -> !loaded;
            default -> true;
        };
    }

    private static @NotNull List<String> filterLabels(@NotNull Player player) {
        return FILTERS.stream().map(option -> MultiverseCards.text(player, KEY + "filter." + option)).toList();
    }

    private static @NotNull ItemStack header(@NotNull Player player, @NotNull List<MVWorld> worlds,
                                             @NotNull WorldFactory factory) {
        long loaded = worlds.stream().filter(world -> factory.isWorldLoaded(world.getIdentifier())).count();
        return MultiverseCards.card(Material.ENDER_EYE, MultiverseCards.ic(player, KEY + "header.name"),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, KEY + "header.description"))
                        .block(List.of(
                                MultiverseCards.row(player, "worlds", MultiverseCards.value(player,
                                        String.valueOf(worlds.size()))),
                                MultiverseCards.row(player, "loaded", MultiverseCards.tone(player, "ok",
                                        String.valueOf(loaded)))))
                        .build());
    }

    private static @NotNull ItemStack worldCard(@NotNull Player player, @NotNull MVWorld world,
                                                @NotNull WorldFactory factory) {
        boolean loaded = factory.isWorldLoaded(world.getIdentifier());
        List<Component> rows = List.of(
                MultiverseCards.row(player, "type", MultiverseCards.word(player, PLAIN,
                        "type-" + world.getType().name().toLowerCase(Locale.ROOT))),
                MultiverseCards.row(player, "environment", MultiverseCards.word(player, PLAIN,
                        WorldEditorCards.environmentWord(world.getEnvironment()))),
                MultiverseCards.row(player, "status", loaded
                        ? MultiverseCards.word(player, "ok", "loaded")
                        : MultiverseCards.word(player, "bad", "unloaded")),
                MultiverseCards.row(player, "global-spawn", MultiverseCards.state(player, world.isGlobalizedSpawn())),
                MultiverseCards.row(player, "pvp", MultiverseCards.state(player, world.isPvpEnabled())),
                MultiverseCards.row(player, "spawn", MultiverseCards.value(player, world.getFormattedSpawnLocation())));
        return MultiverseCards.card(icon(world), MultiverseCards.ic(MultiverseCards.msg(KEY + "entry.name")
                        .with(KEY_WORLD_NAME, MultiverseCards.escape(world.getIdentifier())), player),
                CardLore.create()
                        .block(rows)
                        .block(List.of(MultiverseCards.action(player, KEY + "entry.left"),
                                MultiverseCards.action(player, KEY + "entry.right")))
                        .build());
    }

    private static @NotNull Material icon(@NotNull MVWorld world) {
        return switch (world.getType()) {
            case VOID -> Material.GLASS;
            case PLOT -> Material.OAK_FENCE;
            default -> environmentIcon(world.getEnvironment());
        };
    }

    private static @NotNull Material environmentIcon(@NotNull World.Environment environment) {
        return switch (environment) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }
}
