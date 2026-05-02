package de.jexcellence.multiverse.service;

import de.jexcellence.multiverse.config.PlotWorldConfig;
import de.jexcellence.multiverse.database.entity.Plot;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Block-update geometry for the 4 outer-edge walls of a single plot.
 *
 * <p>Used at claim / unclaim / {@code /plot border} time to swap the
 * perimeter wall material. Edge cells that face a merged-group neighbour are
 * skipped — those walls are intentionally absent so the merged group reads
 * as one continuous space.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class PlotWallOps {

    private PlotWallOps() {}

    /**
     * Repaints the entire perimeter wall of {@code plot} with {@code material}.
     * The four edges of the plot are walked at {@code y = plotHeight + 1 ..
     * plotHeight + wallHeight}. Edge cells whose adjacent grid cell belongs
     * to {@code mergeNeighbours} are skipped.
     */
    public static void applyWalls(@NotNull World world,
                                   @NotNull Plot plot,
                                   int plotSize, int roadWidth,
                                   @NotNull PlotWorldConfig config,
                                   @NotNull Material material,
                                   @NotNull Set<Long> mergedNeighbourIds,
                                   @NotNull java.util.function.Function<int[], Plot> neighbourLookup) {
        int wallHeight = config.wallHeight();
        if (wallHeight <= 0) return;
        int plotHeight = config.plotHeight();
        int interval = plotSize + roadWidth;
        int x0 = plot.getGridX() * interval;
        int z0 = plot.getGridZ() * interval;

        // Walls sit on the ROAD-side column adjacent to the plot — one block
        // outside the plot interior. Plot at gridX,gridZ has road-side wall
        // columns at:
        //   north: z = z0 - 1
        //   south: z = z0 + plotSize
        //   west:  x = x0 - 1
        //   east:  x = x0 + plotSize
        // Stripe length runs the full plot edge so corner road cells get
        // walls from both axes.

        if (!neighbourMerged(neighbourLookup, plot.getGridX(), plot.getGridZ() - 1, mergedNeighbourIds)) {
            stripeX(world, x0, x0 + plotSize - 1, z0 - 1, plotHeight, wallHeight, material);
        }
        if (!neighbourMerged(neighbourLookup, plot.getGridX(), plot.getGridZ() + 1, mergedNeighbourIds)) {
            stripeX(world, x0, x0 + plotSize - 1, z0 + plotSize, plotHeight, wallHeight, material);
        }
        if (!neighbourMerged(neighbourLookup, plot.getGridX() - 1, plot.getGridZ(), mergedNeighbourIds)) {
            stripeZ(world, x0 - 1, z0, z0 + plotSize - 1, plotHeight, wallHeight, material);
        }
        if (!neighbourMerged(neighbourLookup, plot.getGridX() + 1, plot.getGridZ(), mergedNeighbourIds)) {
            stripeZ(world, x0 + plotSize, z0, z0 + plotSize - 1, plotHeight, wallHeight, material);
        }
    }

    private static boolean neighbourMerged(@NotNull java.util.function.Function<int[], Plot> lookup,
                                            int gridX, int gridZ,
                                            @NotNull Set<Long> mergedIds) {
        var neighbour = lookup.apply(new int[]{gridX, gridZ});
        return neighbour != null && mergedIds.contains(neighbour.getId());
    }

    private static void stripeX(@NotNull World world, int minX, int maxX, int z,
                                 int plotHeight, int wallHeight, @NotNull Material material) {
        for (int x = minX; x <= maxX; x++) {
            for (int dy = 1; dy <= wallHeight; dy++) {
                world.getBlockAt(x, plotHeight + dy, z).setType(material, false);
            }
        }
    }

    private static void stripeZ(@NotNull World world, int x, int minZ, int maxZ,
                                 int plotHeight, int wallHeight, @NotNull Material material) {
        for (int z = minZ; z <= maxZ; z++) {
            for (int dy = 1; dy <= wallHeight; dy++) {
                world.getBlockAt(x, plotHeight + dy, z).setType(material, false);
            }
        }
    }
}
