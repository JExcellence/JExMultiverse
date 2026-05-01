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
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
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
}
