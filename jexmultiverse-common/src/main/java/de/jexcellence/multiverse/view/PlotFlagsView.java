package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.view.BaseView;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.api.MultiverseProvider;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.PlotFlag;
import de.jexcellence.multiverse.service.PlotService;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Toggle slots for the four plot flags ({@code pvp}, {@code mob-spawning},
 * {@code explosion}, {@code fire-spread}). Click toggles the flag and
 * refreshes the slot in place.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotFlagsView extends BaseView {

    private final State<JavaPlugin>        pluginState  = initialState(PlotMenuView.DATA_PLUGIN);
    private final State<Plot>              plotState    = initialState(PlotMenuView.DATA_PLOT);
    private final State<PlotService>       serviceState = initialState(PlotMenuView.DATA_SERVICE);
    private final State<MultiverseService> mvState      = initialState(PlotMenuView.DATA_MULTIVERSE);

    public PlotFlagsView() {
        super(PlotMenuView.class);
    }

    @Override
    protected String translationKey() {
        return "plot_flags_ui";
    }

    @Override
    protected String[] layout() {
        return new String[]{
                "         ",
                " P M E F ",
                "    <    "
        };
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull OpenContext open) {
        var plot = plotState.get(open);
        return Map.of(
                "world_name", plot.getWorldName(),
                "grid_x", plot.getGridX(),
                "grid_z", plot.getGridZ());
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        var plot = plotState.get(render);
        var service = serviceState.get(render);

        renderFlag(render, player, plot, service, 'P', PlotFlag.PVP);
        renderFlag(render, player, plot, service, 'M', PlotFlag.MOB_SPAWNING);
        renderFlag(render, player, plot, service, 'E', PlotFlag.EXPLOSION);
        renderFlag(render, player, plot, service, 'F', PlotFlag.FIRE_SPREAD);
    }

    private void renderFlag(@NotNull RenderContext render, @NotNull Player player,
                            @NotNull Plot plot, @NotNull PlotService service,
                            char slot, @NotNull PlotFlag flag) {
        render.layoutSlot(slot, flagItem(player, plot, service, flag))
                .onClick(click -> {
                    click.setCancelled(true);
                    var current = service.getFlag(plot, flag);
                    var plugin = pluginState.get(click);
                    service.setFlag(plot, flag, !current).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (ok) {
                            click.getClickedContainer().renderItem(
                                    click.getClickedSlot(),
                                    flagItem(player, plot, service, flag));
                        }
                        var keyMsg = ok ? "plot.flag_set" : "plot.error.flag_failed";
                        R18nManager.getInstance().msg(keyMsg).prefix()
                                .with("flag", flag.key())
                                .with("value", String.valueOf(!current))
                                .send(click.getPlayer());
                    }));
                });
    }

    private @NotNull ItemStack flagItem(@NotNull Player player, @NotNull Plot plot,
                                         @NotNull PlotService service, @NotNull PlotFlag flag) {
        var on = service.getFlag(plot, flag);
        var override = service.hasFlagOverride(plot, flag);
        var material = switch (flag) {
            case PVP -> on ? Material.DIAMOND_SWORD : Material.WOODEN_SWORD;
            case MOB_SPAWNING -> on ? Material.ZOMBIE_HEAD : Material.BONE;
            case EXPLOSION -> on ? Material.TNT : Material.GUNPOWDER;
            case FIRE_SPREAD -> on ? Material.FIRE_CHARGE : Material.WATER_BUCKET;
        };
        return createItem(
                material,
                i18n("flag." + flag.key() + ".name", player)
                        .withPlaceholder("value", on ? "enabled" : "disabled")
                        .build().component(),
                i18n("flag." + flag.key() + ".lore", player)
                        .withPlaceholders(Map.of(
                                "value", on ? "enabled" : "disabled",
                                "source", override ? "override" : "default"
                        )).build().children()
        );
    }
}
