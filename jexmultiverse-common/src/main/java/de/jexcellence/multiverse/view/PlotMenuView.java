package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.style.LockedIcon;
import de.jexcellence.multiverse.database.entity.MemberRole;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.PlotFlag;
import de.jexcellence.multiverse.service.PlotService;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.state.State;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * The {@code /plot menu} hub: a header card with the plot's owner, location and members, and four cards that
 * open the members list, open the flags, teleport to the plot centre and unclaim the plot (shift-click).
 *
 * <p>Required initial-data keys: {@code plugin}, {@code plot}, {@code service}, {@code multiverse}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotMenuView extends MultiverseBaseView {

    static final String DATA_PLUGIN = "plugin";
    static final String DATA_PLOT = "plot";
    static final String DATA_SERVICE = "service";
    static final String DATA_MULTIVERSE = "multiverse";

    static final String KEY = MultiverseCards.ROOT + "plot_menu.";
    private static final String GRID_X = "grid_x";
    private static final String GRID_Z = "grid_z";
    private static final String DESCRIPTION = ".description";
    private static final String NAME = ".name";
    private static final int HUB_ROW = 2;

    private final State<JavaPlugin> pluginState = initialState(DATA_PLUGIN);
    private final State<Plot> plotState = initialState(DATA_PLOT);
    private final State<PlotService> serviceState = initialState(DATA_SERVICE);
    private final State<MultiverseService> mvState = initialState(DATA_MULTIVERSE);

    @Override
    protected String translationKey() {
        return "mv_gui.plot_menu";
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull OpenContext open) {
        Plot plot = plotState.get(open);
        return Map.of(GRID_X, plot.getGridX(), GRID_Z, plot.getGridZ());
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        Plot plot = plotState.get(render);
        PlotService service = serviceState.get(render);
        PlotActions actions = new PlotActions(pluginState.get(render), service, mvState.get(render));
        Map<String, Object> data = dataMap(plot, pluginState.get(render), service, mvState.get(render));

        render.slot(MultiverseLayout.SLOT_HEADER, header(player, plot, service));
        int[] hub = MultiverseLayout.spacedRow(4, HUB_ROW);
        render.slot(hub[0], membersCard(player, plot, service))
                .onClick(click -> click.openForPlayer(PlotMembersView.class, data));
        render.slot(hub[1], flagsCard(player, plot, actions))
                .onClick(click -> click.openForPlayer(PlotFlagsView.class, data));
        renderHome(render, player, plot, actions, hub[2]);
        render.slot(hub[3], unclaimCard(player)).onClick(click -> {
            if (click.isShiftClick()) {
                click.closeForPlayer();
                actions.unclaim(click.getPlayer(), plot);
            }
        });
    }

    private void renderHome(@NotNull RenderContext render, @NotNull Player player, @NotNull Plot plot,
                            @NotNull PlotActions actions, int slot) {
        if (actions.homeOf(plot) == null) {
            render.slot(slot, MultiverseCards.notice(player, LockedIcon.item(player), KEY + "home.unavailable"));
            return;
        }
        render.slot(slot, actionCard(player, Material.ENDER_PEARL, "home", List.of()))
                .onClick(click -> {
                    click.closeForPlayer();
                    actions.teleportHome(click.getPlayer(), plot);
                });
    }

    /**
     * The plot's header card: owner head, location rows and member counts.
     *
     * @param player  the viewer
     * @param plot    the plot
     * @param service the plot service
     * @return the card
     */
    static @NotNull ItemStack header(@NotNull Player player, @NotNull Plot plot, @NotNull PlotService service) {
        Map<?, MemberRole> members = service.getMembers(plot);
        long trusted = members.values().stream().filter(role -> role == MemberRole.TRUSTED).count();
        long denied = members.values().stream().filter(role -> role == MemberRole.DENIED).count();
        String merged = plot.getMergedGroupIdString() != null ? "merged" : "standalone";
        List<Component> location = List.of(
                MultiverseCards.row(player, "owner", MultiverseCards.tone(player, "accent", plot.getOwnerName())),
                MultiverseCards.row(player, "world", MultiverseCards.value(player, plot.getWorldName())),
                MultiverseCards.row(player, "grid", MultiverseCards.value(player, grid(plot))),
                MultiverseCards.row(player, "merge", MultiverseCards.word(player, "plain", merged)));
        List<Component> memberRows = List.of(
                MultiverseCards.row(player, "trusted", MultiverseCards.tone(player, "ok", String.valueOf(trusted))),
                MultiverseCards.row(player, "denied", MultiverseCards.tone(player, "bad", String.valueOf(denied))));
        return MultiverseCards.card(MultiverseCards.head(plot.getOwnerUuid()),
                MultiverseCards.ic(MultiverseCards.msg(KEY + "header.name")
                        .with("owner_name", MultiverseCards.escape(plot.getOwnerName())), player),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, KEY + "header" + DESCRIPTION))
                        .section(MultiverseCards.section(player, "plot"), location)
                        .section(MultiverseCards.section(player, "members"), memberRows)
                        .build());
    }

    private static @NotNull ItemStack membersCard(@NotNull Player player, @NotNull Plot plot,
                                                  @NotNull PlotService service) {
        String count = String.valueOf(service.getMembers(plot).size());
        return actionCard(player, Material.PLAYER_HEAD, "members",
                List.of(MultiverseCards.row(player, "members", MultiverseCards.value(player, count))));
    }

    private static @NotNull ItemStack flagsCard(@NotNull Player player, @NotNull Plot plot,
                                                @NotNull PlotActions actions) {
        String changed = MultiverseCards.msg(MultiverseCards.COMMON + "value.of-total")
                .with("have", actions.overrides(plot)).with("total", PlotFlag.values().length)
                .miniMessage(player);
        return actionCard(player, Material.OAK_SIGN, "flags",
                List.of(MultiverseCards.row(player, "changed", changed)));
    }

    private static @NotNull ItemStack unclaimCard(@NotNull Player player) {
        return MultiverseCards.card(Material.TNT, MultiverseCards.ic(player, KEY + "unclaim" + NAME),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, KEY + "unclaim" + DESCRIPTION))
                        .block(List.of(MultiverseCards.action(player, KEY + "unclaim.action")))
                        .build());
    }

    private static @NotNull ItemStack actionCard(@NotNull Player player, @NotNull Material icon,
                                                 @NotNull String card, @NotNull List<Component> rows) {
        CardLore lore = CardLore.create().block(MultiverseCards.paragraphOf(player, KEY + card + DESCRIPTION));
        if (!rows.isEmpty()) {
            lore.block(rows);
        }
        lore.block(List.of(MultiverseCards.action(player, KEY + card + ".action")));
        return MultiverseCards.card(icon, MultiverseCards.ic(player, KEY + card + NAME), lore.build());
    }

    /** @return {@code x, z} of the plot's grid position. */
    static @NotNull String grid(@NotNull Plot plot) {
        return plot.getGridX() + ", " + plot.getGridZ();
    }

    /**
     * Builds the initial-data map required to open any plot view.
     *
     * @param plot    the target plot
     * @param plugin  the owning plugin instance
     * @param service the plot service
     * @param mv      the multiverse service
     * @return an immutable map keyed by the {@code DATA_*} constants
     */
    public static @NotNull Map<String, Object> dataMap(@NotNull Plot plot, @NotNull JavaPlugin plugin,
                                                       @NotNull PlotService service, @NotNull MultiverseService mv) {
        return Map.of(
                DATA_PLUGIN, plugin,
                DATA_PLOT, plot,
                DATA_SERVICE, service,
                DATA_MULTIVERSE, mv
        );
    }
}
