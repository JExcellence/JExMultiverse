package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.view.PaginatedView;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.database.entity.MemberRole;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.PlotService;
import me.devnatan.inventoryframework.component.BukkitItemComponentBuilder;
import me.devnatan.inventoryframework.context.Context;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Paginated list of every {@link MemberRole#TRUSTED} and
 * {@link MemberRole#DENIED} member of a plot. Click an entry to remove that
 * role.
 *
 * <p>Adding members is delegated to the existing {@code /plot trust} and
 * {@code /plot deny} commands — that's tab-complete-friendly and avoids
 * needing an anvil-input view here.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotMembersView extends PaginatedView<PlotMembersView.Entry> {

    private final State<JavaPlugin>        pluginState  = initialState(PlotMenuView.DATA_PLUGIN);
    private final State<Plot>              plotState    = initialState(PlotMenuView.DATA_PLOT);
    private final State<PlotService>       serviceState = initialState(PlotMenuView.DATA_SERVICE);
    private final State<MultiverseService> mvState      = initialState(PlotMenuView.DATA_MULTIVERSE);

    public PlotMembersView() {
        super(PlotMenuView.class);
    }

    @Override
    protected String translationKey() {
        return "plot_members_ui";
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
    protected @NotNull CompletableFuture<List<Entry>> loadData(@NotNull Context ctx) {
        var plot = plotState.get(ctx);
        var service = serviceState.get(ctx);
        var members = service.getMembers(plot);
        var rows = new ArrayList<Entry>(members.size());
        members.forEach((uuid, role) -> {
            var name = Bukkit.getOfflinePlayer(uuid).getName();
            rows.add(new Entry(uuid, name != null ? name : uuid.toString().substring(0, 8), role));
        });
        rows.sort((a, b) -> {
            var c = a.role().compareTo(b.role()); // TRUSTED first
            return c != 0 ? c : a.name().compareToIgnoreCase(b.name());
        });
        return CompletableFuture.completedFuture(rows);
    }

    @Override
    protected void renderItem(@NotNull Context ctx,
                              @NotNull BukkitItemComponentBuilder builder,
                              int index,
                              @NotNull Entry entry) {
        var player = ctx.getPlayer();
        var roleKey = entry.role() == MemberRole.TRUSTED ? "trusted" : "denied";
        var item = createItem(
                Material.PLAYER_HEAD,
                i18n("entry." + roleKey + ".name", player)
                        .withPlaceholder("member_name", entry.name())
                        .build().component(),
                i18n("entry." + roleKey + ".lore", player)
                        .withPlaceholders(Map.of(
                                "member_name", entry.name(),
                                "index", index + 1
                        )).build().children()
        );
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.uuid()));
            item.setItemMeta(meta);
        }
        builder.withItem(item).onClick(click -> {
            click.setCancelled(true);
            var p = click.getPlayer();
            var plot = plotState.get(click);
            var service = serviceState.get(click);
            var plugin = pluginState.get(click);
            service.removeMember(plot, entry.uuid()).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                R18nManager.getInstance()
                        .msg(ok ? "plot." + (entry.role() == MemberRole.TRUSTED ? "untrusted" : "undenied")
                                : "plot.error.member_failed").prefix()
                        .with("target_name", entry.name()).send(p);
                // Re-open to refresh the paginated list.
                click.openForPlayer(PlotMembersView.class,
                        PlotMenuView.dataMap(plot, plugin, service, mvState.get(click)));
            }));
        });
    }

    public record Entry(@NotNull UUID uuid, @NotNull String name, @NotNull MemberRole role) {}
}
