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
     * True if the player should be prevented from acting at the location:
     * PLOT world, location is on a claimed plot, and the player isn't owner /
     * trusted / bypassing.
     */
    private boolean denyBuild(@NotNull Player player, @NotNull org.bukkit.Location location) {
        var plot = plots.getPlotAt(location).orElse(null);
        if (plot == null) return false;
        if (plots.canBuild(player, plot)) return false;

        // Throttle chat feedback to one message per second so a shift-place
        // doesn't spam dozens of identical lines.
        var meta = player.getMetadata(WARN_KEY);
        long now = System.currentTimeMillis();
        long last = meta.isEmpty() ? 0L : meta.get(0).asLong();
        if (now - last >= 1000L) {
            player.setMetadata(WARN_KEY, new FixedMetadataValue(plugin, now));
            R18nManager.getInstance()
                    .msg("plot.protection.denied").prefix()
                    .with("owner_name", plot.getOwnerName())
                    .send(player);
        }
        return true;
    }
}
