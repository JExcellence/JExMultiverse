package de.jexcellence.multiverse.listener;

import de.jexcellence.multiverse.protection.BuildLockInteractionMode;
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
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerEggThrowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Build-lock protection for managed worlds. Build actions are always denied in a
 * locked world unless the player is operator or in /mv build; block interactions
 * are controlled by the world's {@link BuildLockInteractionMode}.
 */
public class WorldProtectionListener implements Listener {

    private final MultiverseService mv;
    private final BuildModeService buildMode;

    /**
     * Creates the build-lock protection listener.
     *
     * @param mv multiverse service
     * @param buildMode temporary staff build-mode service
     */
    public WorldProtectionListener(@NotNull MultiverseService mv, @NotNull BuildModeService buildMode) {
        this.mv = mv;
        this.buildMode = buildMode;
    }

    /** Prevents block breaking in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Prevents block placement in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Prevents fluid pickup in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(@NotNull PlayerBucketFillEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Prevents fluid placement in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(@NotNull PlayerBucketEmptyEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Applies the configured interaction profile for build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!denied(player)) {
            return;
        }
        Action action = event.getAction();
        BuildLockInteractionMode mode = mv.buildLockInteractionMode(player.getWorld());
        if (isModifierItem(event.getMaterial())) {
            event.setCancelled(true);
            return;
        }
        if (action == Action.PHYSICAL) {
            event.setCancelled(mode != BuildLockInteractionMode.OPEN);
            return;
        }
        if (action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (mode == BuildLockInteractionMode.LOCKED) {
            event.setCancelled(true);
            return;
        }
        var block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Material target = block.getType();
        if (target == Material.DECORATED_POT || isBlockedContainer(target)) {
            event.setCancelled(true);
            return;
        }
        if (mode == BuildLockInteractionMode.SAFE && isSafeBlockedInteraction(target)) {
            event.setCancelled(true);
            return;
        }
        if (target.isInteractable()) {
            return;
        }
    }

    /** Prevents armor stand edits in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(@NotNull PlayerArmorStandManipulateEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Prevents hanging entity removal in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(@NotNull HangingBreakByEntityEvent event) {
        Player player = playerFrom(event.getRemover());
        if (player != null && denied(player)) {
            event.setCancelled(true);
        }
    }

    /** Prevents hanging entity placement in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(@NotNull HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null && denied(player)) {
            event.setCancelled(true);
        }
    }

    /** Prevents entity damage initiated by denied players in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamage(@NotNull EntityDamageByEntityEvent event) {
        Player player = playerFrom(event.getDamager());
        if (player != null && denied(player)) {
            event.setCancelled(true);
        }
    }

    /** Prevents thrown eggs from hatching in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEggThrow(@NotNull PlayerEggThrowEvent event) {
        if (denied(event.getPlayer())) {
            event.setHatching(false);
        }
    }

    /** Cancels egg and spawn-egg creature spawns in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCreatureSpawn(@NotNull CreatureSpawnEvent event) {
        World world = event.getLocation().getWorld();
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (world != null && mv.isBuildLocked(world)
                && (reason == CreatureSpawnEvent.SpawnReason.EGG
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG)) {
            event.setCancelled(true);
        }
    }

    /** Prevents denied players from trampling farmland in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(@NotNull EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND
                && event.getEntity() instanceof Player player && denied(player)) {
            event.setCancelled(true);
        }
    }

    /** Prevents bone meal fertilization by denied players in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(@NotNull BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (player != null && denied(player)) {
            event.setCancelled(true);
        }
    }

    /** Prevents denied players from editing signs in build-locked worlds. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(@NotNull SignChangeEvent event) {
        if (denied(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private boolean denied(@NotNull Player player) {
        World world = player.getWorld();
        if (!mv.isBuildLocked(world)) {
            return false;
        }
        return !player.isOp() && !buildMode.isEnabled(player.getUniqueId());
    }

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

    private static boolean isSafeBlockedInteraction(@NotNull Material mat) {
        String name = mat.name();
        if (name.contains("SIGN") || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")
                || name.endsWith("_FENCE_GATE") || name.endsWith("_BUTTON")
                || name.endsWith("_PRESSURE_PLATE")) {
            return true;
        }
        return switch (mat) {
            case LEVER, BELL, NOTE_BLOCK, JUKEBOX, CAKE, CANDLE_CAKE, RESPAWN_ANCHOR,
                 WHITE_BED, ORANGE_BED, MAGENTA_BED, LIGHT_BLUE_BED, YELLOW_BED, LIME_BED,
                 PINK_BED, GRAY_BED, LIGHT_GRAY_BED, CYAN_BED, PURPLE_BED, BLUE_BED,
                 BROWN_BED, GREEN_BED, RED_BED, BLACK_BED -> true;
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
                || material == Material.GLOW_ITEM_FRAME || material == Material.PAINTING
                || material == Material.EGG || material == Material.ENDER_PEARL) {
            return true;
        }
        String name = material.name();
        return name.endsWith("_SPAWN_EGG") || name.endsWith("_HOE") || name.endsWith("_SHOVEL")
                || name.endsWith("_BUCKET") || name.endsWith("_BOAT") || name.endsWith("_MINECART");
    }

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
