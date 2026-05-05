package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.view.PaginatedView;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.factory.WorldFactory;
import de.jexcellence.multiverse.service.MultiverseService;
import me.devnatan.inventoryframework.component.BukkitItemComponentBuilder;
import me.devnatan.inventoryframework.context.Context;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Paginated overview of all managed worlds.
 *
 * <p>Each entry is clickable:
 * <ul>
 *   <li>Left-click — teleport to the world's spawn</li>
 *   <li>Right-click — open the {@link MultiverseEditorView} for that world</li>
 *   <li>Shift-click — close (delete is intentionally restricted to /mv delete to
 *       prevent accidental destruction from a list)</li>
 * </ul>
 *
 * <p>Required initial-data keys:
 * <ul>
 *   <li>{@code "plugin"}  — {@link JavaPlugin}</li>
 *   <li>{@code "service"} — {@link MultiverseService}</li>
 *   <li>{@code "factory"} — {@link WorldFactory}</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class MultiverseListView extends PaginatedView<MVWorld> {

    private final State<JavaPlugin>        pluginState  = initialState("plugin");
    private final State<MultiverseService> serviceState = initialState("service");
    private final State<WorldFactory>      factoryState = initialState("factory");

    public MultiverseListView() {
        super();
    }

    @Override
    protected String translationKey() {
        return "multiverse_list_ui";
    }

    @Override
    protected @NotNull CompletableFuture<List<MVWorld>> loadData(@NotNull Context ctx) {
        var worlds = new ArrayList<>(serviceState.get(ctx).getAllWorldEntities());
        worlds.sort((a, b) -> a.getIdentifier().compareToIgnoreCase(b.getIdentifier()));
        return CompletableFuture.completedFuture(worlds);
    }

    @Override
    protected void renderItem(@NotNull Context ctx,
                              @NotNull BukkitItemComponentBuilder builder,
                              int index,
                              @NotNull MVWorld entry) {
        var player  = ctx.getPlayer();
        var factory = factoryState.get(ctx);
        var loaded  = factory.isWorldLoaded(entry.getIdentifier());

        var icon = switch (entry.getType()) {
            case VOID    -> Material.BARRIER;
            case PLOT    -> Material.GRASS_BLOCK;
            case DEFAULT -> entry.getEnvironment() == org.bukkit.World.Environment.NETHER ? Material.NETHERRACK
                          : entry.getEnvironment() == org.bukkit.World.Environment.THE_END ? Material.END_STONE
                          : Material.GRASS_BLOCK;
        };

        var placeholders = Map.<String, Object>of(
                "world_name",   entry.getIdentifier(),
                "type",         entry.getType().name(),
                "environment",  entry.getEnvironment().name(),
                "status",       loaded ? "loaded" : "unloaded",
                "global_spawn", entry.isGlobalizedSpawn() ? "yes" : "no",
                "pvp",          entry.isPvpEnabled() ? "yes" : "no",
                "spawn",        entry.getFormattedSpawnLocation(),
                "index",        index + 1
        );

        builder.withItem(createItem(
                icon,
                i18n("entry.name", player).withPlaceholders(placeholders).build().component(),
                i18n("entry.lore", player).withPlaceholders(placeholders).build().children()
        )).onClick(click -> {
            var p       = click.getPlayer();
            var plugin  = pluginState.get(click);
            var service = serviceState.get(click);

            if (click.isRightClick()) {
                click.openForPlayer(MultiverseEditorView.class, Map.of(
                        "plugin",  plugin,
                        "world",   entry,
                        "service", service
                ));
                return;
            }

            // Left-click: teleport
            var bukkit = factory.getBukkitWorld(entry.getIdentifier());
            if (bukkit.isEmpty()) {
                R18nManager.getInstance()
                        .msg("multiverse.world_not_loaded")
                        .prefix()
                        .with("world_name", entry.getIdentifier())
                        .send(p);
                return;
            }
            var spawn = entry.getSpawnLocation() != null
                    ? entry.getSpawnLocation()
                    : bukkit.get().getSpawnLocation();
            click.closeForPlayer();
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.teleport(spawn);
                R18nManager.getInstance()
                        .msg("multiverse.teleported")
                        .prefix()
                        .with("world_name", entry.getIdentifier())
                        .send(p);
            });
        });
    }

    @Override
    protected void onPaginatedRender(@NotNull me.devnatan.inventoryframework.context.RenderContext render,
                                      @NotNull Player player) {
        // No extra chrome beyond the default paginated layout.
    }
}
