package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.PlotFlag;
import de.jexcellence.multiverse.service.PlotService;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
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
 * One card per plot flag, centred in the body. A click flips the flag and redraws its card and the header in
 * place; the chat confirms the change.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotFlagsView extends MultiverseBaseView {

    static final String KEY = MultiverseCards.ROOT + "plot_flags.";
    private static final String GRID_X = "grid_x";
    private static final String GRID_Z = "grid_z";

    private final State<JavaPlugin> pluginState = initialState(PlotMenuView.DATA_PLUGIN);
    private final State<Plot> plotState = initialState(PlotMenuView.DATA_PLOT);
    private final State<PlotService> serviceState = initialState(PlotMenuView.DATA_SERVICE);
    private final State<MultiverseService> mvState = initialState(PlotMenuView.DATA_MULTIVERSE);

    @Override
    protected String translationKey() {
        return "mv_gui.plot_flags";
    }

    @Override
    protected String backDestination() {
        return "plot-menu";
    }

    @Override
    protected void onBack(@NotNull SlotClickContext click) {
        click.openForPlayer(PlotMenuView.class, copyData(click));
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
        render.slot(MultiverseLayout.SLOT_HEADER, header(player, plot, actions));
        PlotFlag[] flags = PlotFlag.values();
        int[] slots = MultiverseLayout.centred(flags.length);
        for (int i = 0; i < slots.length; i++) {
            PlotFlag flag = flags[i];
            int slot = slots[i];
            render.slot(slot, flagCard(player, plot, service, flag)).onClick(click -> {
                Player clicker = click.getPlayer();
                actions.setFlag(clicker, plot, flag, !service.getFlag(plot, flag), () -> {
                    click.getClickedContainer().renderItem(slot, flagCard(clicker, plot, service, flag));
                    click.getClickedContainer().renderItem(MultiverseLayout.SLOT_HEADER,
                            header(clicker, plot, actions));
                });
            });
        }
    }

    private static @NotNull ItemStack header(@NotNull Player player, @NotNull Plot plot,
                                             @NotNull PlotActions actions) {
        String changed = MultiverseCards.msg(MultiverseCards.COMMON + "value.of-total")
                .with("have", actions.overrides(plot)).with("total", PlotFlag.values().length)
                .miniMessage(player);
        return MultiverseCards.card(Material.OAK_SIGN, MultiverseCards.ic(MultiverseCards.msg(KEY + "header.name")
                        .with(GRID_X, plot.getGridX()).with(GRID_Z, plot.getGridZ()), player),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, KEY + "header.description"))
                        .block(List.of(MultiverseCards.row(player, "changed", changed)))
                        .build());
    }

    /**
     * The card of one flag: what it does, its state, where the value comes from and what a click does.
     *
     * @param player  the viewer
     * @param plot    the plot
     * @param service the plot service
     * @param flag    the flag
     * @return the card
     */
    static @NotNull ItemStack flagCard(@NotNull Player player, @NotNull Plot plot, @NotNull PlotService service,
                                       @NotNull PlotFlag flag) {
        boolean enabled = service.getFlag(plot, flag);
        String source = service.hasFlagOverride(plot, flag) ? "override" : "default";
        String base = KEY + "flag." + flag.key();
        List<Component> rows = List.of(
                MultiverseCards.row(player, "status", MultiverseCards.state(player, enabled)),
                MultiverseCards.row(player, "source", MultiverseCards.word(player, "muted", source)));
        ItemStack card = MultiverseCards.card(icon(flag, enabled), MultiverseCards.ic(player, base + ".name"),
                CardLore.create()
                        .block(MultiverseCards.paragraphOf(player, base + ".description"))
                        .block(rows)
                        .block(List.of(MultiverseCards.action(player, KEY + (enabled ? "turn-off" : "turn-on"))))
                        .build());
        return enabled ? MultiverseCards.glint(card) : card;
    }

    private static @NotNull Material icon(@NotNull PlotFlag flag, boolean enabled) {
        return switch (flag) {
            case PVP -> enabled ? Material.DIAMOND_SWORD : Material.WOODEN_SWORD;
            case MOB_SPAWNING -> enabled ? Material.ZOMBIE_HEAD : Material.BONE;
            case EXPLOSION -> enabled ? Material.TNT : Material.GUNPOWDER;
            case FIRE_SPREAD -> enabled ? Material.FIRE_CHARGE : Material.WATER_BUCKET;
            case KEEP_INVENTORY -> enabled ? Material.TOTEM_OF_UNDYING : Material.SKELETON_SKULL;
            case ENTRY -> enabled ? Material.OAK_DOOR : Material.IRON_DOOR;
            case LIQUID_FLOW -> enabled ? Material.WATER_BUCKET : Material.BUCKET;
            case ICE_FORM_MELT -> enabled ? Material.ICE : Material.PACKED_ICE;
            default -> Material.PAPER;
        };
    }
}
