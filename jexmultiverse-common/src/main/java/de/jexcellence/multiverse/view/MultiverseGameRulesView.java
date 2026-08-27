package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.view.PaginatedView;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.service.MultiverseService;
import me.devnatan.inventoryframework.component.BukkitItemComponentBuilder;
import me.devnatan.inventoryframework.context.Context;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Paginated per-world gamerule editor.
 *
 * <p>Lists every boolean gamerule the running server knows about, read from
 * {@code Registry.GAME_RULE} rather than a hard-coded list, so a server on a newer
 * Minecraft version picks up new rules without a plugin change. Integer gamerules are
 * deliberately excluded: they cannot be edited by clicking, and showing them as
 * unclickable entries would be worse than leaving them to {@code /gamerule}.
 *
 * <p>Each entry has three states rather than two:
 * <ul>
 *   <li><b>Unmanaged</b> - JExMultiverse does not touch the rule, and the world keeps
 *       whatever the server gives it. This is the default and is not persisted.</li>
 *   <li><b>Managed true</b> / <b>Managed false</b> - the value is stored on the world
 *       and re-applied every time it loads.</li>
 * </ul>
 *
 * <p>Left-click cycles unmanaged to true to false and back. Right-click drops straight
 * back to unmanaged, which is otherwise two clicks away.
 *
 * <p>Changes are staged on the in-memory {@link MVWorld} and previewed on the live
 * world, matching {@link MultiverseEditorView}. Nothing is written to the database
 * until Save is pressed back in the editor.
 *
 * @author JExcellence
 * @since 3.7.0
 */
public class MultiverseGameRulesView extends PaginatedView<GameRule<?>> {

    private static final String KEY_RULE   = "rule";
    private static final String KEY_VALUE  = "value";
    private static final String VAL_TRUE      = "true";
    private static final String VAL_FALSE     = "false";
    private static final String VAL_UNMANAGED = "unmanaged";

    private final State<JavaPlugin>        pluginState  = initialState(MultiverseEditorView.DATA_PLUGIN);
    private final State<MVWorld>           worldState   = initialState(MultiverseEditorView.DATA_WORLD);
    private final State<MultiverseService> serviceState = initialState(MultiverseEditorView.DATA_SERVICE);
    private final State<WorldFactory>      factoryState = initialState(MultiverseEditorView.DATA_FACTORY);

    public MultiverseGameRulesView() {
        super(MultiverseEditorView.class);
    }

    @Override
    protected String translationKey() {
        return "multiverse_gamerules_ui";
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull OpenContext open) {
        return Map.of("world_name", worldState.get(open).getIdentifier());
    }

    @Override
    protected CompletableFuture<List<GameRule<?>>> loadData(@NotNull Context ctx) {
        // Registry rather than GameRule.values(): the constants are deprecated for
        // removal, and the registry reflects what this server build actually has.
        List<GameRule<?>> rules = Registry.GAME_RULE.stream()
                .filter(rule -> rule.getType() == Boolean.class)
                .sorted(Comparator.comparing(WorldFactory::gameRuleName))
                .toList();
        return CompletableFuture.completedFuture(rules);
    }

    @Override
    protected void renderItem(@NotNull Context ctx,
                              @NotNull BukkitItemComponentBuilder builder,
                              int index,
                              @NotNull GameRule<?> rule) {
        var player = ctx.getPlayer();
        var world  = worldState.get(ctx);

        builder.withItem(ruleIcon(player, world, rule)).onClick(click -> {
            click.setCancelled(true);
            var target = worldState.get(click);
            var rules  = new HashMap<>(target.getGameRules());

            if (click.isRightClick()) {
                rules.remove(WorldFactory.gameRuleName(rule));
            } else {
                cycle(rules, WorldFactory.gameRuleName(rule));
            }
            target.setGameRules(rules);

            var live = Bukkit.getWorld(target.getIdentifier());
            if (live != null) {
                factoryState.get(click).applyWorldSettings(live, target);
            }

            R18nManager.getInstance()
                    .msg("multiverse_gamerules_ui.updated").prefix()
                    .with(KEY_RULE, WorldFactory.gameRuleName(rule))
                    .with(KEY_VALUE, describe(rules.get(WorldFactory.gameRuleName(rule))))
                    .send(click.getPlayer());

            click.update();
        });
    }

    /**
     * Advances a rule through unmanaged, true, false and back to unmanaged.
     *
     * @param rules the mutable staged rule map
     * @param name  the gamerule name
     */
    private static void cycle(@NotNull Map<String, String> rules, @NotNull String name) {
        var current = rules.get(name);
        if (current == null) {
            rules.put(name, VAL_TRUE);
        } else if (VAL_TRUE.equalsIgnoreCase(current)) {
            rules.put(name, VAL_FALSE);
        } else {
            rules.remove(name);
        }
    }

    private @NotNull org.bukkit.inventory.ItemStack ruleIcon(@NotNull Player player,
                                                             @NotNull MVWorld world,
                                                             @NotNull GameRule<?> rule) {
        var stored = world.getGameRules().get(WorldFactory.gameRuleName(rule));
        var live = liveValue(world, rule);
        var material = materialFor(stored, live);

        var placeholders = new HashMap<String, Object>();
        placeholders.put(KEY_RULE, WorldFactory.gameRuleName(rule));
        placeholders.put(KEY_VALUE, describe(stored));
        placeholders.put("effective", effectiveValue(world, rule));

        return createItem(
                material,
                i18n("entry.name", player).withPlaceholders(placeholders).build().component(),
                i18n("entry.lore", player).withPlaceholders(placeholders).build().children()
        );
    }

    /**
     * Managed rules use their stored value's colour so the click state is obvious.
     * Unmanaged rules fall through to the LIVE value so an admin can see at a
     * glance which rules the world currently reports as enabled, instead of every
     * unmanaged rule being gray (which used to hide whether mobGriefing was actually
     * on or off on that world). GRAY only when the world is unloaded.
     */
    private static @NotNull Material materialFor(String stored, @org.jetbrains.annotations.Nullable Boolean live) {
        if (stored != null) {
            return VAL_TRUE.equalsIgnoreCase(stored) ? Material.LIME_DYE : Material.RED_DYE;
        }
        if (live == null) {
            return Material.GRAY_DYE;
        }
        return live ? Material.LIME_DYE : Material.RED_DYE;
    }

    private static @org.jetbrains.annotations.Nullable Boolean liveValue(@NotNull MVWorld world, @NotNull GameRule<?> rule) {
        var live = Bukkit.getWorld(world.getIdentifier());
        if (live == null) {
            return null;
        }
        var value = live.getGameRuleValue(rule);
        return value instanceof Boolean b ? b : null;
    }

    private static @NotNull String describe(String stored) {
        return stored == null ? VAL_UNMANAGED : stored.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the value the live world currently reports for a rule.
     *
     * <p>Shown alongside the managed value so an admin can see when a rule was
     * changed out from under JExMultiverse, for example by {@code /gamerule}.
     *
     * @param world the managed world
     * @param rule  the gamerule
     * @return the live value, or {@code "-"} when the world is not loaded
     */
    private static @NotNull String effectiveValue(@NotNull MVWorld world, @NotNull GameRule<?> rule) {
        var live = Bukkit.getWorld(world.getIdentifier());
        if (live == null) {
            return "-";
        }
        var value = live.getGameRuleValue(rule);
        return value == null ? "-" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }
}
