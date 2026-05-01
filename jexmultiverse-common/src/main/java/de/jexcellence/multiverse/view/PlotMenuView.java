package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.view.BaseView;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.api.MultiverseProvider;
import de.jexcellence.multiverse.database.entity.MemberRole;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.PlotService;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Main {@code /plot menu} hub: shows owner + plot info and buttons to open
 * the members editor, the flags editor, teleport to plot, and unclaim.
 *
 * <p>Required initial-data keys: {@code plugin}, {@code plot},
 * {@code service}, {@code multiverse}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotMenuView extends BaseView {

    static final String DATA_PLUGIN     = "plugin";
    static final String DATA_PLOT       = "plot";
    static final String DATA_SERVICE    = "service";
    static final String DATA_MULTIVERSE = "multiverse";

    private final State<JavaPlugin>        pluginState  = initialState(DATA_PLUGIN);
    private final State<Plot>              plotState    = initialState(DATA_PLOT);
    private final State<PlotService>       serviceState = initialState(DATA_SERVICE);
    private final State<MultiverseService> mvState      = initialState(DATA_MULTIVERSE);

    public PlotMenuView() {
        super();
    }

    @Override
    protected String translationKey() {
        return "plot_menu_ui";
    }

    @Override
    protected String[] layout() {
        return new String[]{
                "    I    ",
                " M F H U ",
                "         "
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
        var plugin = pluginState.get(render);
        var mv = mvState.get(render);

        renderInfo(render, player, plot, service);
        renderMembers(render, player, plot, plugin, service, mv);
        renderFlags(render, player, plot, plugin, service, mv);
        renderHome(render, player, plot, plugin, service, mv);
        renderUnclaim(render, player, plot, plugin, service, mv);
    }

    // ── Info head ───────────────────────────────────────────────────────────────

    private void renderInfo(@NotNull RenderContext render, @NotNull Player player,
                            @NotNull Plot plot, @NotNull PlotService service) {
        var members = service.getMembers(plot);
        long trusted = members.values().stream().filter(r -> r == MemberRole.TRUSTED).count();
        long denied  = members.values().stream().filter(r -> r == MemberRole.DENIED).count();
        var owner = Bukkit.getOfflinePlayer(plot.getOwnerUuid());

        var item = createItem(
                Material.PLAYER_HEAD,
                i18n("info.name", player)
                        .withPlaceholder("owner_name", plot.getOwnerName())
                        .build().component(),
                i18n("info.lore", player)
                        .withPlaceholders(Map.of(
                                "owner_name", plot.getOwnerName(),
                                "world_name", plot.getWorldName(),
                                "grid_x", plot.getGridX(),
                                "grid_z", plot.getGridZ(),
                                "trusted", trusted,
                                "denied", denied,
                                "merged", plot.getMergedGroupIdString() != null ? "yes" : "no"
                        )).build().children()
        );
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(owner);
            item.setItemMeta(meta);
        }
        render.layoutSlot('I', item);
    }

    // ── Members ─────────────────────────────────────────────────────────────────

    private void renderMembers(@NotNull RenderContext render, @NotNull Player player,
                               @NotNull Plot plot, @NotNull JavaPlugin plugin,
                               @NotNull PlotService service, @NotNull MultiverseService mv) {
        var item = createItem(
                Material.PLAYER_HEAD,
                i18n("members.name", player).build().component(),
                i18n("members.lore", player).build().children()
        );
        render.layoutSlot('M', item).onClick(click -> {
            click.setCancelled(true);
            click.openForPlayer(PlotMembersView.class, dataMap(plot, plugin, service, mv));
        });
    }

    // ── Flags ───────────────────────────────────────────────────────────────────

    private void renderFlags(@NotNull RenderContext render, @NotNull Player player,
                             @NotNull Plot plot, @NotNull JavaPlugin plugin,
                             @NotNull PlotService service, @NotNull MultiverseService mv) {
        var item = createItem(
                Material.OAK_SIGN,
                i18n("flags.name", player).build().component(),
                i18n("flags.lore", player).build().children()
        );
        render.layoutSlot('F', item).onClick(click -> {
            click.setCancelled(true);
            click.openForPlayer(PlotFlagsView.class, dataMap(plot, plugin, service, mv));
        });
    }

    // ── Home (teleport) ─────────────────────────────────────────────────────────

    private void renderHome(@NotNull RenderContext render, @NotNull Player player,
                            @NotNull Plot plot, @NotNull JavaPlugin plugin,
                            @NotNull PlotService service, @NotNull MultiverseService mv) {
        var item = createItem(
                Material.ENDER_PEARL,
                i18n("home.name", player).build().component(),
                i18n("home.lore", player).build().children()
        );
        render.layoutSlot('H', item).onClick(click -> {
            click.setCancelled(true);
            var p = click.getPlayer();
            var bukkitWorld = Bukkit.getWorld(plot.getWorldName());
            if (bukkitWorld == null) return;
            var bounds = mv.plotBounds(plot.getWorldName(), plot.getGridX(), plot.getGridZ()).orElse(null);
            if (bounds == null) return;
            var loc = new org.bukkit.Location(bukkitWorld,
                    bounds.centerX() + 0.5, bounds.surfaceY() + 1, bounds.centerZ() + 0.5);
            click.closeForPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.teleport(loc);
                R18nManager.getInstance().msg("plot.teleported").prefix()
                        .with("grid_x", String.valueOf(plot.getGridX()))
                        .with("grid_z", String.valueOf(plot.getGridZ()))
                        .with("world_name", plot.getWorldName())
                        .send(p);
            });
        });
    }

    // ── Unclaim ─────────────────────────────────────────────────────────────────

    private void renderUnclaim(@NotNull RenderContext render, @NotNull Player player,
                               @NotNull Plot plot, @NotNull JavaPlugin plugin,
                               @NotNull PlotService service, @NotNull MultiverseService mv) {
        var item = createItem(
                Material.BARRIER,
                i18n("unclaim.name", player).build().component(),
                i18n("unclaim.lore", player).build().children()
        );
        render.layoutSlot('U', item).onClick(click -> {
            click.setCancelled(true);
            var p = click.getPlayer();
            click.closeForPlayer();
            service.unclaim(plot).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                R18nManager.getInstance()
                        .msg(ok ? "plot.unclaimed" : "plot.error.unclaim_failed")
                        .prefix()
                        .with("grid_x", String.valueOf(plot.getGridX()))
                        .with("grid_z", String.valueOf(plot.getGridZ()))
                        .send(p);
            }));
        });
    }

    // ── Helper ──────────────────────────────────────────────────────────────────

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
