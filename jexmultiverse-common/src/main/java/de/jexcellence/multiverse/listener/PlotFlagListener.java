package de.jexcellence.multiverse.listener;

import de.jexcellence.multiverse.service.PlotFlag;
import de.jexcellence.multiverse.service.PlotService;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Enforces non-PvP plot flags. PvP itself is gated in
 * {@link PlotProtectionListener} alongside the build/interact protections.
 *
 * <ul>
 *   <li>{@link PlotFlag#MOB_SPAWNING} → blocks {@link CreatureSpawnEvent} for
 *       hostile mobs (creatures inheriting {@link org.bukkit.entity.Monster})
 *       inside plots that disabled the flag. Players, ambient, and animals
 *       are unaffected.</li>
 *   <li>{@link PlotFlag#EXPLOSION} → strips blocks inside no-explosion plots
 *       from the affected lists of {@link EntityExplodeEvent} and
 *       {@link BlockExplodeEvent}. Other plots / non-plot terrain in the
 *       same explosion still take damage.</li>
 *   <li>{@link PlotFlag#FIRE_SPREAD} → cancels {@link BlockSpreadEvent} when
 *       the source is fire and the target is in a no-fire plot. Also blocks
 *       {@link BlockBurnEvent} so blocks adjacent to fire don't disappear.</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class PlotFlagListener implements Listener {

    private final PlotService plots;

    public PlotFlagListener(@NotNull PlotService plots) {
        this.plots = plots;
    }

    // ── Mob spawning ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCreatureSpawn(@NotNull CreatureSpawnEvent event) {
        // Only gate hostile mobs — peaceful mobs and ambient critters stay vanilla.
        if (!isHostile(event.getEntity())) return;
        // Don't gate spawner / spawn-egg / breeding actions; those are
        // intentional player actions handled elsewhere by build protection.
        switch (event.getSpawnReason()) {
            case NATURAL, JOCKEY, RAID, REINFORCEMENTS, PATROL, SLIME_SPLIT,
                 NETHER_PORTAL, INFECTION, SILVERFISH_BLOCK, OCELOT_BABY -> { /* gate */ }
            default -> { return; }
        }
        var plot = plots.getPlotAt(event.getLocation()).orElse(null);
        if (plot == null) return;
        if (!plots.getFlag(plot, PlotFlag.MOB_SPAWNING)) {
            event.setCancelled(true);
        }
    }

    private static boolean isHostile(@NotNull LivingEntity entity) {
        if (entity instanceof org.bukkit.entity.Monster) return true;
        // Phantoms and slimes aren't `Monster` but should still be gated.
        return entity instanceof org.bukkit.entity.Phantom
                || entity instanceof org.bukkit.entity.Slime
                || entity instanceof org.bukkit.entity.Ghast
                || entity instanceof org.bukkit.entity.Hoglin
                || entity instanceof org.bukkit.entity.Shulker;
    }

    // ── Explosions ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(@NotNull EntityExplodeEvent event) {
        event.blockList().removeIf(block -> {
            var plot = plots.getPlotAt(block.getLocation()).orElse(null);
            return plot != null && !plots.getFlag(plot, PlotFlag.EXPLOSION);
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(@NotNull BlockExplodeEvent event) {
        event.blockList().removeIf(block -> {
            var plot = plots.getPlotAt(block.getLocation()).orElse(null);
            return plot != null && !plots.getFlag(plot, PlotFlag.EXPLOSION);
        });
    }

    // ── Fire spread / burn ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockSpread(@NotNull BlockSpreadEvent event) {
        Block source = event.getSource();
        if (source.getType() != Material.FIRE) return;
        var plot = plots.getPlotAt(event.getBlock().getLocation()).orElse(null);
        if (plot == null) return;
        if (!plots.getFlag(plot, PlotFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBurn(@NotNull BlockBurnEvent event) {
        var plot = plots.getPlotAt(event.getBlock().getLocation()).orElse(null);
        if (plot == null) return;
        if (!plots.getFlag(plot, PlotFlag.FIRE_SPREAD)) {
            event.setCancelled(true);
        }
    }

    // ── Keep inventory ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        var plot = plots.getPlotAt(event.getEntity().getLocation()).orElse(null);
        if (plot == null) return;
        if (plots.getFlag(plot, PlotFlag.KEEP_INVENTORY)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    // ── Liquid flow (water + lava) ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLiquidFlow(@NotNull BlockFromToEvent event) {
        var plot = plots.getPlotAt(event.getToBlock().getLocation()).orElse(null);
        if (plot == null) return;
        if (!plots.getFlag(plot, PlotFlag.LIQUID_FLOW)) {
            event.setCancelled(true);
        }
    }

    // ── Ice / snow form + melt ──────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIceMelt(@NotNull BlockFadeEvent event) {
        var type = event.getBlock().getType();
        if (type != Material.ICE && type != Material.FROSTED_ICE && type != Material.SNOW
                && type != Material.SNOW_BLOCK) return;
        var plot = plots.getPlotAt(event.getBlock().getLocation()).orElse(null);
        if (plot == null) return;
        if (!plots.getFlag(plot, PlotFlag.ICE_FORM_MELT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIceForm(@NotNull BlockFormEvent event) {
        var newType = event.getNewState().getType();
        if (newType != Material.ICE && newType != Material.SNOW
                && newType != Material.SNOW_BLOCK) return;
        var plot = plots.getPlotAt(event.getBlock().getLocation()).orElse(null);
        if (plot == null) return;
        if (!plots.getFlag(plot, PlotFlag.ICE_FORM_MELT)) {
            event.setCancelled(true);
        }
    }

    // ── Entry blocking ──────────────────────────────────────────────────────────
    //
    // Player movement fires every tick, so we only do the cross-plot lookup
    // when the block-x or block-z actually changes. Players who can't enter
    // are pushed back to their previous location.

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        var to = event.getTo();
        var from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) return;

        var plot = plots.getPlotAt(to).orElse(null);
        if (plot == null) return;

        var player = event.getPlayer();
        if (plots.canBuild(player, plot)) return; // owner / trusted / bypass
        if (plots.isDenied(player, plot)) {
            event.setTo(from);
            return;
        }
        if (!plots.getFlag(plot, PlotFlag.ENTRY)) {
            event.setTo(from);
        }
    }
}
