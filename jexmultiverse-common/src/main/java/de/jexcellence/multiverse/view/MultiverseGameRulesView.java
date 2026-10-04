package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.component.FilterHopperButton;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-world gamerule editor: every boolean gamerule the running server knows (read from
 * {@code Registry.GAME_RULE}), 28 per page, filterable by managed and unmanaged.
 *
 * <p>Each rule has three states: <b>unmanaged</b> (JExMultiverse leaves it alone, not persisted),
 * <b>managed on</b> and <b>managed off</b> (stored on the world and re-applied on every load). Left-click
 * cycles unmanaged, on, off; right-click drops straight back to unmanaged.
 *
 * <p>Changes are staged on the in-memory {@link MVWorld} and previewed on the live world, matching
 * {@link MultiverseEditorView}. Nothing is written to the database until Save is pressed in the editor.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MultiverseGameRulesView extends MultiverseBaseView {

    static final String KEY = MultiverseCards.ROOT + "gamerules.";
    static final List<String> FILTERS = List.of("all", "managed", "unmanaged");
    static final FilterHopperButton FILTER = new FilterHopperButton("jexmultiverse:gamerules", FILTERS.size());

    private static final String VAL_TRUE = "true";
    private static final String VAL_FALSE = "false";
    private static final String UNMANAGED = "unmanaged";
    private static final String MUTED = "muted";

    private final State<MVWorld> worldState = initialState(MultiverseEditorView.DATA_WORLD);
    private final State<WorldFactory> factoryState = initialState(MultiverseEditorView.DATA_FACTORY);

    @Override
    protected String translationKey() {
        return "mv_gui.gamerules";
    }

    @Override
    protected String backDestination() {
        return "world-editor";
    }

    @Override
    protected void onBack(@NotNull SlotClickContext click) {
        Map<String, Object> data = copyData(click);
        data.remove(DATA_PAGE);
        click.openForPlayer(MultiverseEditorView.class, data);
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull OpenContext open) {
        return Map.of("world_name", worldState.get(open).getIdentifier());
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        MVWorld world = worldState.get(render);
        int filter = FILTER.index(player.getUniqueId());
        List<GameRule<?>> rules = rules(world, filter);

        render.slot(MultiverseLayout.SLOT_HEADER, header(player, world));
        render.slot(MultiverseLayout.SLOT_FILTER, MultiverseCards.filter(player, filterLabels(player), filter))
                .onClick(click -> {
                    FILTER.cycle(click.getPlayer().getUniqueId(), !click.isRightClick());
                    reopen(click, 0);
                });
        if (rules.isEmpty()) {
            render.slot(MultiverseLayout.centreSlot(),
                    MultiverseCards.notice(player, new ItemStack(Material.PAPER), KEY + "empty"));
            return;
        }
        int pages = MultiverseLayout.pageCount(rules.size());
        int page = MultiverseLayout.clampPage(requestedPage(render), pages);
        int from = page * MultiverseLayout.PAGE_SIZE;
        int to = Math.min(rules.size(), from + MultiverseLayout.PAGE_SIZE);
        int[] slots = MultiverseLayout.centred(to - from);
        for (int i = 0; i < slots.length; i++) {
            GameRule<?> rule = rules.get(from + i);
            render.slot(slots[i], ruleCard(player, world, rule)).onClick(click -> onRuleClick(click, rule));
        }
        pagination(render, player, page, pages);
    }

    private void onRuleClick(@NotNull SlotClickContext click, @NotNull GameRule<?> rule) {
        MVWorld target = worldState.get(click);
        String name = WorldFactory.gameRuleName(rule);
        Map<String, String> staged = new HashMap<>(target.getGameRules());
        if (click.isRightClick()) {
            staged.remove(name);
        } else {
            cycle(staged, name);
        }
        target.setGameRules(staged);
        World live = Bukkit.getWorld(target.getIdentifier());
        if (live != null) {
            factoryState.get(click).applyWorldSettings(live, target);
        }
        MultiverseCards.msg("multiverse_gamerules_ui.updated").prefix()
                .with("rule", name)
                .with("value", MultiverseCards.text(click.getPlayer(), WorldEditorCards.WORD + stateWord(staged.get(name))))
                .send(click.getPlayer());
        click.getClickedContainer().renderItem(click.getClickedSlot(), ruleCard(click.getPlayer(), target, rule));
        click.getClickedContainer().renderItem(MultiverseLayout.SLOT_HEADER, header(click.getPlayer(), target));
    }

    private static @NotNull List<GameRule<?>> rules(@NotNull MVWorld world, int filter) {
        Map<String, String> stored = world.getGameRules();
        return Registry.GAME_RULE.stream()
                .filter(rule -> rule.getType() == Boolean.class)
                .filter(rule -> matches(filter, stored.containsKey(WorldFactory.gameRuleName(rule))))
                .sorted(Comparator.comparing(WorldFactory::gameRuleName))
                .toList();
    }

    private static boolean matches(int filter, boolean managed) {
        return switch (filter) {
            case 1 -> managed;
            case 2 -> !managed;
            default -> true;
        };
    }

    private static void cycle(@NotNull Map<String, String> rules, @NotNull String name) {
        String current = rules.get(name);
        if (current == null) {
            rules.put(name, VAL_TRUE);
        } else if (VAL_TRUE.equalsIgnoreCase(current)) {
            rules.put(name, VAL_FALSE);
        } else {
            rules.remove(name);
        }
    }

    private static @NotNull List<String> filterLabels(@NotNull Player player) {
        return FILTERS.stream().map(option -> MultiverseCards.text(player, KEY + "filter." + option)).toList();
    }

    private static @NotNull ItemStack header(@NotNull Player player, @NotNull MVWorld world) {
        return MultiverseCards.card(Material.COMMAND_BLOCK, MultiverseCards.ic(MultiverseCards.msg(KEY + "header.name")
                        .with("world_name", MultiverseCards.escape(world.getIdentifier())), player),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, KEY + "header.description"))
                        .block(List.of(MultiverseCards.row(player, "managed",
                                MultiverseCards.value(player, String.valueOf(world.getGameRules().size())))))
                        .build());
    }

    private static @NotNull ItemStack ruleCard(@NotNull Player player, @NotNull MVWorld world,
                                               @NotNull GameRule<?> rule) {
        String name = WorldFactory.gameRuleName(rule);
        String stored = world.getGameRules().get(name);
        Boolean live = liveValue(world, rule);
        List<Component> rows = List.of(
                MultiverseCards.row(player, "managed", storedValue(player, stored)),
                MultiverseCards.row(player, "live", liveText(player, live)));
        return MultiverseCards.card(icon(stored, live),
                MultiverseCards.ic(MultiverseCards.msg(KEY + "entry.name").with("rule", name), player),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, KEY + "entry.description"))
                        .block(rows)
                        .block(List.of(MultiverseCards.action(player, KEY + "entry.left"),
                                MultiverseCards.action(player, KEY + "entry.right")))
                        .build());
    }

    private static @NotNull String storedValue(@NotNull Player player, @Nullable String stored) {
        if (stored == null) {
            return MultiverseCards.word(player, MUTED, UNMANAGED);
        }
        return MultiverseCards.state(player, VAL_TRUE.equalsIgnoreCase(stored));
    }

    private static @NotNull String liveText(@NotNull Player player, @Nullable Boolean live) {
        return live == null ? MultiverseCards.word(player, MUTED, "not-loaded") : MultiverseCards.state(player, live);
    }

    private static @NotNull String stateWord(@Nullable String stored) {
        if (stored == null) {
            return UNMANAGED;
        }
        return VAL_TRUE.equalsIgnoreCase(stored) ? "enabled" : "disabled";
    }

    /**
     * Managed rules show their stored value; unmanaged rules show the live value so an admin sees what the
     * world reports, and gray only when the world is not loaded.
     */
    private static @NotNull Material icon(@Nullable String stored, @Nullable Boolean live) {
        if (stored != null) {
            return VAL_TRUE.equalsIgnoreCase(stored) ? Material.LIME_DYE : Material.RED_DYE;
        }
        if (live == null) {
            return Material.GRAY_DYE;
        }
        return Boolean.TRUE.equals(live) ? Material.LIME_DYE : Material.RED_DYE;
    }

    private static @Nullable Boolean liveValue(@NotNull MVWorld world, @NotNull GameRule<?> rule) {
        World live = Bukkit.getWorld(world.getIdentifier());
        if (live == null) {
            return null;
        }
        return live.getGameRuleValue(rule) instanceof Boolean value ? value : null;
    }
}
