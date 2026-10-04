package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.component.FilterHopperButton;
import de.jexcellence.multiverse.database.entity.MemberRole;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.PlotService;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Every trusted and denied member of a plot, filterable by role, 28 per page. Clicking a member removes the
 * role. Adding members stays with {@code /plot trust} and {@code /plot deny}, which tab-complete names.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotMembersView extends MultiverseBaseView {

    static final String KEY = MultiverseCards.ROOT + "plot_members.";
    /** Filter options in cycle order: all, trusted, denied. */
    static final List<String> FILTERS = List.of("all", "trusted", "denied");
    static final FilterHopperButton FILTER = new FilterHopperButton("jexmultiverse:plot-members", FILTERS.size());

    private static final String GRID_X = "grid_x";
    private static final String GRID_Z = "grid_z";

    private final State<JavaPlugin> pluginState = initialState(PlotMenuView.DATA_PLUGIN);
    private final State<Plot> plotState = initialState(PlotMenuView.DATA_PLOT);
    private final State<PlotService> serviceState = initialState(PlotMenuView.DATA_SERVICE);
    private final State<MultiverseService> mvState = initialState(PlotMenuView.DATA_MULTIVERSE);

    @Override
    protected String translationKey() {
        return "mv_gui.plot_members";
    }

    @Override
    protected String backDestination() {
        return "plot-menu";
    }

    @Override
    protected void onBack(@NotNull SlotClickContext click) {
        Map<String, Object> data = copyData(click);
        data.remove(DATA_PAGE);
        click.openForPlayer(PlotMenuView.class, data);
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
        int filter = FILTER.index(player.getUniqueId());
        List<PlotActions.Member> shown = filtered(actions.members(plot), filter);

        render.slot(MultiverseLayout.SLOT_HEADER, PlotMenuView.header(player, plot, service));
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
        boolean manage = PlotActions.canManage(player, plot);
        for (int i = 0; i < slots.length; i++) {
            PlotActions.Member member = shown.get(from + i);
            if (!manage) {
                render.slot(slots[i], memberCard(player, member, false));
                continue;
            }
            render.slot(slots[i], memberCard(player, member, true)).onClick(click ->
                    actions.removeMember(click.getPlayer(), plot, member, () -> reopen(click, page)));
        }
        pagination(render, player, page, pages);
    }

    /**
     * The members that match a filter option.
     *
     * @param members every member
     * @param filter  the filter index ({@link #FILTERS})
     * @return the matching members
     */
    static @NotNull List<PlotActions.Member> filtered(@NotNull List<PlotActions.Member> members, int filter) {
        return switch (filter) {
            case 1 -> members.stream().filter(member -> member.role() == MemberRole.TRUSTED).toList();
            case 2 -> members.stream().filter(member -> member.role() == MemberRole.DENIED).toList();
            default -> members;
        };
    }

    private static @NotNull List<String> filterLabels(@NotNull Player player) {
        return FILTERS.stream().map(option -> MultiverseCards.text(player, KEY + "filter." + option)).toList();
    }

    private static @NotNull ItemStack memberCard(@NotNull Player player, @NotNull PlotActions.Member member,
                                                 boolean manage) {
        boolean trusted = member.role() == MemberRole.TRUSTED;
        String role = trusted ? "trusted" : "denied";
        return MultiverseCards.card(MultiverseCards.head(member.uuid()),
                MultiverseCards.ic(MultiverseCards.msg(KEY + "entry.name")
                        .with("member_name", MultiverseCards.escape(member.name())), player),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, KEY + "entry." + role))
                        .block(List.of(MultiverseCards.row(player, "role",
                                MultiverseCards.word(player, trusted ? "ok" : "bad", role))))
                        .block(List.of(manage
                                ? MultiverseCards.action(player, KEY + "remove." + role)
                                : MultiverseCards.ownerOnly(player)))
                        .build());
    }
}
