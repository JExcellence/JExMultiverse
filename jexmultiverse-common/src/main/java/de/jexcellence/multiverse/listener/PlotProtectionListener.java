package de.jexcellence.multiverse.listener;

import de.jexcellence.jextranslate.R18nManager;
import de.jexcellence.multiverse.service.MultiverseService;
import de.jexcellence.multiverse.service.PlotFlag;
import de.jexcellence.multiverse.service.PlotService;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Gates block / interaction events in PLOT worlds against plot ownership +
 * the trusted/denied member lists. All checks are synchronous reads against
 * {@link PlotService}'s in-memory cache.
 *
 * <p>Non-PLOT worlds and locations on roads are passed through untouched.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotProtectionListener implements Listener {

    private static final String WARN_KEY = "jexplots.last_warn";

    private final PlotService plots;
    private final MultiverseService mv;
    private final JavaPlugin plugin;

    public PlotProtectionListener(@NotNull PlotService plots, @NotNull MultiverseService mv,
                                   @NotNull JavaPlugin plugin) {
        this.plots = plots;
        this.mv = mv;
        this.plugin = plugin;
    }

    // ── Block place / break ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        if (denyBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (denyBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(@NotNull PlayerBucketFillEvent event) {
        if (denyBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(@NotNull PlayerBucketEmptyEvent event) {
        if (denyBuild(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    // ── Interact (containers, redstone, doors, etc.) ────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        var block = event.getClickedBlock();
        if (block == null) return;
        if (denyBuild(event.getPlayer(), block.getLocation())) {
            event.setCancelled(true);
        }
    }

    // ── PvP ─────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPvp(@NotNull EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        var plot = plots.getPlotAt(victim.getLocation()).orElse(null);
        if (plot == null) return; // road or unclaimed = vanilla rules apply
        // PvP allowed when: both are owner/trusted (any PvP among friends), OR
        // the plot's `pvp` flag is enabled (anyone can fight here).
        if (plots.canBuild(attacker, plot) && plots.canBuild(victim, plot)) return;
        if (plots.getFlag(plot, PlotFlag.PVP)) return;
        event.setCancelled(true);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * True if the player should be prevented from acting at the location.
     * Three cases:
     * <ol>
     *   <li>Location is on a claimed plot and player is neither owner /
     *       trusted nor holds {@code jexplots.bypass.protect} → deny.</li>
     *   <li>Location is on a road / border in a PLOT world and player
     *       doesn't hold {@code jexplots.bypass.roads} → deny. Roads are
     *       treated as protected public infrastructure by default; staff
     *       with the bypass perm can still maintain them.</li>
     *   <li>Otherwise → allow.</li>
     * </ol>
     */
    private boolean denyBuild(@NotNull Player player, @NotNull org.bukkit.Location location) {
        var plot = plots.getPlotAt(location).orElse(null);
        if (plot != null) {
            if (plots.canBuild(player, plot)) return false;
            warnDenied(player, "plot.protection.denied",
                    java.util.Map.of("owner_name", plot.getOwnerName()));
            return true;
        }
        // No claimed plot at this location. If we're in a PLOT world the
        // location is on a road or unclaimed plot — protect the road grid
        // unless the player has the bypass permission.
        if (mv.isRoadOrBorder(location)) {
            if (player.hasPermission("jexplots.bypass.roads")
                    || player.hasPermission("jexplots.bypass.protect")) return false;
            warnDenied(player, "plot.protection.road_denied", java.util.Map.of());
            return true;
        }
        return false;
    }

    private void warnDenied(@NotNull Player player, @NotNull String key,
                             @NotNull java.util.Map<String, Object> placeholders) {
        var meta = player.getMetadata(WARN_KEY);
        long now = System.currentTimeMillis();
        long last = meta.isEmpty() ? 0L : meta.get(0).asLong();
        if (now - last < 1000L) return;
        player.setMetadata(WARN_KEY, new FixedMetadataValue(plugin, now));
        var builder = R18nManager.getInstance().msg(key).prefix();
        placeholders.forEach((k, v) -> builder.with(k, String.valueOf(v)));
        builder.send(player);
    }
}
