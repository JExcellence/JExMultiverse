package de.jexcellence.multiverse.listener;

import de.jexcellence.jexplatform.schematic.edit.SelectionService;
import de.jexcellence.jextranslate.R18nManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/**
 * Binds the {@link SelectionService} golden-axe wand: left-click a block sets
 * corner 1, right-click sets corner 2. Wand interactions are cancelled so they
 * never break or place blocks.
 *
 * @author JExcellence
 * @since 4.0.0
 */
public final class SelectionWandListener implements Listener {

    private final SelectionService selections;

    public SelectionWandListener(@NotNull SelectionService selections) {
        this.selections = selections;
    }

    @EventHandler(ignoreCancelled = false)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        if (!selections.isWand(event.getItem())) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Location clicked = event.getClickedBlock().getLocation();
        if (action == Action.LEFT_CLICK_BLOCK) {
            Location set = selections.setPos1(player, clicked);
            sendCorner(player, "multiverse.edit.pos1_set", set);
        } else {
            Location set = selections.setPos2(player, clicked);
            sendCorner(player, "multiverse.edit.pos2_set", set);
        }
    }

    private void sendCorner(@NotNull Player player, @NotNull String key, @NotNull Location at) {
        R18nManager.getInstance().msg(key).prefix()
                .with("x", String.valueOf(at.getBlockX()))
                .with("y", String.valueOf(at.getBlockY()))
                .with("z", String.valueOf(at.getBlockZ()))
                .with("count", selections.selection(player.getUniqueId())
                        .map(s -> String.valueOf(s.blockCount())).orElse("—"))
                .send(player);
    }
}
