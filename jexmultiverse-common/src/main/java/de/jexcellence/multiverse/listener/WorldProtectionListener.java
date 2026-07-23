package de.jexcellence.multiverse.listener;

import de.jexcellence.multiverse.service.BuildModeService;
import de.jexcellence.multiverse.service.MultiverseService;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Build-lock protection for managed worlds — a lightweight, per-world WorldGuard.
 * In a world flagged build-locked (see {@code MVWorld#isBuildLocked} / {@code
 * /mv lock}) world-modifying actions are cancelled: block break / place, bucket
 * use, left-clicking blocks, armour-stand item swaps, hanging (item-frame /
 * painting) break &amp; place, and player-dealt entity damage (so NPCs/mobs can't
 * be hit). <b>Right-click "use" interactions stay allowed</b> — players can still
 * right-click NPCs and entities, open crates and containers, and use doors /
 * buttons. Actual placement attempted via right-click is still blocked by the
 * block-place handler.
 *
 * <p>Two bypasses, both deliberate: <b>operators</b> always pass, and players in
 * <b>build mode</b> ({@code /mv build}, gated by {@code jexmultiverse.build})
 * pass. Unmanaged worlds are never touched. Denied actions fail <b>silently</b> —
 * no chat message. All checks are synchronous cache reads via
 * {@link MultiverseService#isBuildLocked(World)}.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public class WorldProtectionListener implements Listener {

    private final MultiverseService mv;
    private final BuildModeService buildMode;

    public WorldProtectionListener(@NotNull MultiverseService mv, @NotNull BuildModeService buildMode) {
        this.mv = mv;
        this.buildMode = buildMode;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(@NotNull PlayerBucketFillEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(@NotNull PlayerBucketEmptyEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        Action action = event.getAction();
        // Left-click is NOT cancelled here — BlockBreakEvent already prevents
        // actual breaking in locked worlds, and cancelling LEFT_CLICK_BLOCK
        // blocks third-party plugins (e.g. AdvancedCrates preview).
        if (action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // Genuine "use" — opening a crate / container / door / button — stays
        // allowed. But item-driven modification with no place/break event of its
        // own (fire, spawn eggs, bonemeal, tilling, entity placement) is blocked
        // on non-interactable targets.
        var block = event.getClickedBlock();
        // Decorated pots (deco "vases") swallow the held item on right-click even
        // though isInteractable() is false and the item isn't a "modifier" — so a
        // plain right-click (e.g. holding a Trident) silently deposits it into the
        // pot, unrecoverable without breaking the block. Always block that in a
        // locked world.
        if (block != null && block.getType() == Material.DECORATED_POT && denied(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        // Storage + utility blocks (chests, furnaces, grindstone, anvil, …) are
        // otherwise "genuine use" and would be allowed; in a locked world (spawn)
        // players shouldn't open/use them. Doors, buttons, and non-vanilla crate
        // blocks are NOT in this set, so navigation + AdvancedCrates still work.
        if (block != null && isBlockedContainer(block.getType()) && denied(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        if (block != null && block.getType().isInteractable()) {
            return;
        }
        if (isModifierItem(event.getMaterial()) && denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(@NotNull PlayerArmorStandManipulateEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(@NotNull HangingBreakByEntityEvent event) {
        Player player = playerFrom(event.getRemover());
        if (player != null && denied(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(@NotNull HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && denied(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamage(@NotNull EntityDamageByEntityEvent event) {
        Player player = playerFrom(event.getDamager());
        if (player != null && denied(player)) {
            event.setCancelled(true);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * True if the player must be stopped from acting in their current world:
     * the world is build-locked AND the player is neither an operator nor in
     * build mode. Sends a throttled denial message as a side effect.
     */
    private boolean denied(@NotNull Player player) {
        World world = player.getWorld();
        if (!mv.isBuildLocked(world)) {
            return false;
        }
        if (player.isOp() || buildMode.isEnabled(player.getUniqueId())) {
            return false;
        }
        // Locked, no bypass — cancel silently (no chat spam for staff/visitors).
        return true;
    }

    /**
     * Items that modify the world on right-click without firing a place/break
     * event of their own — fire starters, spawn eggs, bonemeal, terrain tools,
     * liquids, and entity-placement items. These are blocked even though general
     * right-click "use" is allowed, so a locked world can't be set alight,
     * populated with mobs, tilled, or littered with placed entities.
     */
    /**
     * Storage + utility blocks blocked from use in a locked world (spawn). Doors,
     * buttons, levers, and non-vanilla crate blocks are intentionally excluded so
     * navigation and AdvancedCrates keep working. NOTE: a crate placed on a vanilla
     * CHEST/BARREL will also be blocked — exclude its location if that's the case.
     */
    private static boolean isBlockedContainer(@NotNull Material mat) {
        if (org.bukkit.Tag.SHULKER_BOXES.isTagged(mat)) {
            return true;
        }
        return switch (mat) {
            case CHEST, TRAPPED_CHEST, BARREL, HOPPER, DROPPER, DISPENSER,
                 FURNACE, BLAST_FURNACE, SMOKER, BREWING_STAND, CRAFTING_TABLE,
                 GRINDSTONE, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL, ENCHANTING_TABLE,
                 STONECUTTER, LOOM, CARTOGRAPHY_TABLE, SMITHING_TABLE, LECTERN, BEACON -> true;
            default -> false;
        };
    }

    private static boolean isModifierItem(@Nullable Material material) {
        if (material == null) {
            return false;
        }
        if (material == Material.FLINT_AND_STEEL || material == Material.FIRE_CHARGE
                || material == Material.BONE_MEAL || material == Material.ARMOR_STAND
                || material == Material.END_CRYSTAL || material == Material.ITEM_FRAME
                || material == Material.GLOW_ITEM_FRAME || material == Material.PAINTING) {
            return true;
        }
        String name = material.name();
        return name.endsWith("_SPAWN_EGG") || name.endsWith("_HOE") || name.endsWith("_SHOVEL")
                || name.endsWith("_BUCKET") || name.endsWith("_BOAT") || name.endsWith("_MINECART");
    }

    /** Resolves the acting player behind an entity (direct, or a fired projectile). */
    private static @Nullable Player playerFrom(@Nullable Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
