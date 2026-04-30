package de.jexcellence.multiverse.view;

import de.jexcellence.jexplatform.view.BaseView;
import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.database.entity.MVWorld;
import de.jexcellence.multiverse.service.MultiverseService;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Inventory Framework view for editing a managed world's settings.
 *
 * <p>Provides toggle/action slots for:
 * <ul>
 *   <li>Setting the world spawn to the player's current location (only allowed
 *       when the player is actually in the world being edited)</li>
 *   <li>Toggling global spawn designation — routed through
 *       {@link MultiverseService#setGlobalSpawn(String)} so other worlds with
 *       the global flag are automatically cleared</li>
 *   <li>Toggling PvP</li>
 *   <li>Saving changes to the database</li>
 * </ul>
 *
 * <p>Click handlers re-open the view to refresh slot icons/lore in place,
 * since IF's static {@code layoutSlot(char, ItemStack)} doesn't re-render
 * dynamic content.
 *
 * <p>Required initial-data keys:
 * <ul>
 *   <li>{@code "plugin"}     — {@link JavaPlugin} instance</li>
 *   <li>{@code "world"}      — {@link MVWorld} being edited</li>
 *   <li>{@code "service"}    — {@link MultiverseService} for persistence</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class MultiverseEditorView extends BaseView {

    private static final String DATA_PLUGIN  = "plugin";
    private static final String DATA_WORLD   = "world";
    private static final String DATA_SERVICE = "service";

    private final State<JavaPlugin>        pluginState  = initialState(DATA_PLUGIN);
    private final State<MVWorld>           worldState   = initialState(DATA_WORLD);
    private final State<MultiverseService> serviceState = initialState(DATA_SERVICE);

    public MultiverseEditorView() {
        super();
    }

    @Override
    protected String translationKey() {
        return "multiverse_editor_ui";
    }

    @Override
    protected String[] layout() {
        return new String[]{
                "         ",
                "  S G P  ",
                "         ",
                "         ",
                "         ",
                "    W    "
        };
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull me.devnatan.inventoryframework.context.OpenContext open) {
        var world = worldState.get(open);
        return Map.of("world_name", world.getIdentifier());
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        var world   = worldState.get(render);
        var service = serviceState.get(render);
        var plugin  = pluginState.get(render);

        renderSpawnSlot(render, player, world, service, plugin);
        renderGlobalSpawnSlot(render, player, world, service, plugin);
        renderPvpSlot(render, player, world, service, plugin);
        renderSaveSlot(render, player, world, service);
    }

    // ── Spawn location ──────────────────────────────────────────────────────────

    private void renderSpawnSlot(@NotNull RenderContext render, @NotNull Player player,
                                 @NotNull MVWorld world, @NotNull MultiverseService service,
                                 @NotNull JavaPlugin plugin) {
        var spawnDisplay = world.getFormattedSpawnLocation();
        render.layoutSlot('S', createItem(
                Material.COMPASS,
                i18n("spawn.name", player)
                        .withPlaceholder("value", spawnDisplay)
                        .build().component(),
                i18n("spawn.lore", player)
                        .withPlaceholder("value", spawnDisplay)
                        .build().children()
        )).onClick(click -> {
            var p = click.getPlayer();
            if (!p.getWorld().getName().equals(world.getIdentifier())) {
                R18nManager.getInstance()
                        .msg("multiverse_editor_ui.spawn.wrong_world")
                        .prefix()
                        .with("world_name", world.getIdentifier())
                        .send(p);
                return;
            }
            world.setSpawnLocation(p.getLocation());
            R18nManager.getInstance()
                    .msg("multiverse_editor_ui.spawn.updated")
                    .prefix()
                    .with("world_name", world.getIdentifier())
                    .send(p);
            reopen(click, world, service, plugin);
        });
    }

    // ── Global spawn toggle ─────────────────────────────────────────────────────

    private void renderGlobalSpawnSlot(@NotNull RenderContext render, @NotNull Player player,
                                       @NotNull MVWorld world, @NotNull MultiverseService service,
                                       @NotNull JavaPlugin plugin) {
        var isGlobal = world.isGlobalizedSpawn();
        render.layoutSlot('G', createItem(
                isGlobal ? Material.NETHER_STAR : Material.ENDER_PEARL,
                i18n("global_spawn.name", player)
                        .withPlaceholder("value", isGlobal ? "enabled" : "disabled")
                        .build().component(),
                i18n("global_spawn.lore", player)
                        .withPlaceholder("value", isGlobal ? "enabled" : "disabled")
                        .build().children()
        )).onClick(click -> {
            var p = click.getPlayer();

            if (world.isGlobalizedSpawn()) {
                world.setGlobalizedSpawn(false);
                service.updateWorld(world);
                R18nManager.getInstance()
                        .msg("multiverse_editor_ui.global_spawn.toggled")
                        .prefix()
                        .with("world_name", world.getIdentifier())
                        .with("value", "disabled")
                        .send(p);
                reopen(click, world, service, plugin);
                return;
            }

            var previous = service.getAllWorldEntities().stream()
                    .filter(MVWorld::isGlobalizedSpawn)
                    .filter(other -> !other.getIdentifier().equals(world.getIdentifier()))
                    .findFirst();

            service.setGlobalSpawn(world.getIdentifier()).thenAccept(success -> {
                if (success) {
                    var r18n = R18nManager.getInstance();
                    previous.ifPresentOrElse(
                            prev -> r18n.msg("multiverse_editor_ui.global_spawn.replaced")
                                    .prefix()
                                    .with("world_name", world.getIdentifier())
                                    .with("previous", prev.getIdentifier())
                                    .send(p),
                            () -> r18n.msg("multiverse_editor_ui.global_spawn.toggled")
                                    .prefix()
                                    .with("world_name", world.getIdentifier())
                                    .with("value", "enabled")
                                    .send(p)
                    );
                }
                reopen(click, world, service, plugin);
            });
        });
    }

    // ── PvP toggle ──────────────────────────────────────────────────────────────

    private void renderPvpSlot(@NotNull RenderContext render, @NotNull Player player,
                               @NotNull MVWorld world, @NotNull MultiverseService service,
                               @NotNull JavaPlugin plugin) {
        var pvp = world.isPvpEnabled();
        render.layoutSlot('P', createItem(
                pvp ? Material.DIAMOND_SWORD : Material.WOODEN_SWORD,
                i18n("pvp.name", player)
                        .withPlaceholder("value", pvp ? "enabled" : "disabled")
                        .build().component(),
                i18n("pvp.lore", player)
                        .withPlaceholder("value", pvp ? "enabled" : "disabled")
                        .build().children()
        )).onClick(click -> {
            var p = click.getPlayer();
            world.setPvpEnabled(!world.isPvpEnabled());
            R18nManager.getInstance()
                    .msg("multiverse_editor_ui.pvp.toggled")
                    .prefix()
                    .with("world_name", world.getIdentifier())
                    .with("value", world.isPvpEnabled() ? "enabled" : "disabled")
                    .send(p);
            reopen(click, world, service, plugin);
        });
    }

    // ── Save button ─────────────────────────────────────────────────────────────

    private void renderSaveSlot(@NotNull RenderContext render, @NotNull Player player,
                                @NotNull MVWorld world, @NotNull MultiverseService service) {
        render.layoutSlot('W', createItem(
                Material.EMERALD,
                i18n("save.name", player).build().component(),
                i18n("save.lore", player)
                        .withPlaceholder("world_name", world.getIdentifier())
                        .build().children()
        )).onClick(click -> {
            var p = click.getPlayer();

            service.updateWorld(world).thenAccept(saved ->
                    R18nManager.getInstance()
                            .msg("multiverse_editor_ui.save.success")
                            .prefix()
                            .with("world_name", saved.getIdentifier())
                            .send(p)
            ).exceptionally(ex -> {
                R18nManager.getInstance()
                        .msg("multiverse_editor_ui.save.failed")
                        .prefix()
                        .with("world_name", world.getIdentifier())
                        .send(p);
                return null;
            });
            click.closeForPlayer();
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private void reopen(@NotNull SlotClickContext click,
                        @NotNull MVWorld world,
                        @NotNull MultiverseService service,
                        @NotNull JavaPlugin plugin) {
        click.openForPlayer(MultiverseEditorView.class, Map.of(
                DATA_PLUGIN,  plugin,
                DATA_WORLD,   world,
                DATA_SERVICE, service
        ));
    }
}
