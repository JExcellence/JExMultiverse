package de.jexcellence.multiverse.listener;

import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.service.BuildModeService;
import de.jexcellence.multiverse.service.MultiverseService;
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
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
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
 * pass. Unmanaged worlds are never touched. All checks are synchronous cache
 * reads via {@link MultiverseService#isBuildLocked(World)}.
 *
 * @author JExcellence
 * @since 3.4.0
 */
public class WorldProtectionListener implements Listener {

    private static final String WARN_KEY = "jexmultiverse.last_build_warn";
    private static final String DENIED_KEY = "multiverse.world_locked_denied";
    private static final long WARN_THROTTLE_MS = 1500L;

    private final MultiverseService mv;
    private final BuildModeService buildMode;
    private final JavaPlugin plugin;

    public WorldProtectionListener(@NotNull MultiverseService mv, @NotNull BuildModeService buildMode,
                                   @NotNull JavaPlugin plugin) {
        this.mv = mv;
        this.buildMode = buildMode;
        this.plugin = plugin;
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
        // "Use" interactions stay allowed so players can right-click crates, NPCs
        // rendered as blocks, containers, doors and buttons. Only left-clicking a
        // block (the start of breaking / punching) is blocked here — actual world
        // modification is still caught by onBreak / onPlace / the bucket handlers.
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && denied(event.getPlayer())) {
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
        warn(player);
        return true;
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

    private void warn(@NotNull Player player) {
        var meta = player.getMetadata(WARN_KEY);
        long now = System.currentTimeMillis();
        long last = meta.isEmpty() ? 0L : meta.get(0).asLong();
        if (now - last < WARN_THROTTLE_MS) {
            return;
        }
        player.setMetadata(WARN_KEY, new FixedMetadataValue(plugin, now));
        R18nManager.getInstance().msg(DENIED_KEY).prefix().send(player);
    }
}
