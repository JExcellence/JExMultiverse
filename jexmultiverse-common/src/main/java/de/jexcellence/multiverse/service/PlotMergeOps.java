package de.jexcellence.multiverse.service;

import de.jexcellence.multiverse.config.PlotWorldConfig;
import de.jexcellence.multiverse.database.entity.Plot;
import de.jexcellence.multiverse.generator.plot.PlotLayer;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Block-update geometry for merging / unmerging adjacent {@link Plot}s.
 *
 * <p>Merge operations fill the road slice between two plots with the plot's
 * configured terrain layers and clear the 3-block wall stripes that face
 * each other. Unmerge restores the road surface (stone + road-material cap)
 * and re-places the wall stripes.
 *
 * <p>All block writes use {@code setType(material, false)} to skip physics —
 * we don't want lighting / fluid updates triggering cascades during a
 * potentially-large fill.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class PlotMergeOps {

    private PlotMergeOps() {}

    /**
     * Returns true iff plots {@code a} and {@code b} sit in the same world and
     * occupy adjacent grid cells (one step on a single axis).
     */
    public static boolean areAdjacent(@NotNull Plot a, @NotNull Plot b) {
        if (!a.getWorldName().equals(b.getWorldName())) return false;
        int dx = Math.abs(a.getGridX() - b.getGridX());
        int dz = Math.abs(a.getGridZ() - b.getGridZ());
        return (dx == 1 && dz == 0) || (dx == 0 && dz == 1);
    }

    // ── Merge: fill road + clear walls ──────────────────────────────────────────

    /** Applies the visual merge between two adjacent plots. */
    public static void applyMerge(@NotNull World world, @NotNull Plot a, @NotNull Plot b,
                                   int plotSize, int roadWidth, @NotNull PlotWorldConfig config) {
        if (!areAdjacent(a, b)) return;
        var slice = roadSlice(a, b, plotSize, roadWidth);
        fillPlotTerrain(world, slice, config.plotHeight(), config.layers());
        clearWallsBetween(world, a, b, plotSize, roadWidth, config.plotHeight(), config.wallHeight());
    }

    /**
     * Applies the visual unmerge: restores road surface + walls between two
     * plots. Wall material is the {@code claimedWallMaterial} the caller
     * passes — typically each plot's own owner-chosen border material, since
     * both plots involved in an unmerge are still claimed.
     */
    public static void applyUnmerge(@NotNull World world, @NotNull Plot a, @NotNull Plot b,
                                     int plotSize, int roadWidth,
                                     @NotNull PlotWorldConfig config,
                                     @NotNull Material claimedWallMaterial) {
        if (!areAdjacent(a, b)) return;
        var slice = roadSlice(a, b, plotSize, roadWidth);
        restoreRoad(world, slice, config.plotHeight(), config.roadMaterial());
        restoreWallsBetween(world, a, b, plotSize, roadWidth, config.plotHeight(),
                config.wallHeight(), claimedWallMaterial);
    }

    // ── Geometry ────────────────────────────────────────────────────────────────

    /**
     * Returns the AABB of the road slice between two adjacent plots.
     * X/Z bounds are inclusive block coordinates.
     */
    private static @NotNull RoadSlice roadSlice(@NotNull Plot a, @NotNull Plot b,
                                                 int plotSize, int roadWidth) {
        int interval = plotSize + roadWidth;
        if (a.getGridX() == b.getGridX()) {
            int north = Math.min(a.getGridZ(), b.getGridZ());
            int south = Math.max(a.getGridZ(), b.getGridZ());
            return new RoadSlice(
                    a.getGridX() * interval,
                    a.getGridX() * interval + plotSize - 1,
                    north * interval + plotSize,
                    south * interval - 1);
        }
        int west = Math.min(a.getGridX(), b.getGridX());
        int east = Math.max(a.getGridX(), b.getGridX());
        return new RoadSlice(
                west * interval + plotSize,
                east * interval - 1,
                a.getGridZ() * interval,
                a.getGridZ() * interval + plotSize - 1);
    }

    private record RoadSlice(int minX, int maxX, int minZ, int maxZ) {}

    // ── Block writes ────────────────────────────────────────────────────────────

    private static void fillPlotTerrain(@NotNull World world, @NotNull RoadSlice slice,
                                         int plotHeight, @NotNull List<PlotLayer> layers) {
        for (int x = slice.minX(); x <= slice.maxX(); x++) {
            for (int z = slice.minZ(); z <= slice.maxZ(); z++) {
                // Clear the road-material surface that previously sat at plotHeight.
                world.getBlockAt(x, plotHeight, z).setType(Material.AIR, false);
                for (var layer : layers) {
                    int y0 = Math.max(layer.minY(), 1);
                    int y1 = Math.min(layer.maxY(), plotHeight);
                    for (int y = y0; y <= y1; y++) {
                        world.getBlockAt(x, y, z).setType(layer.material(), false);
                    }
                }
            }
        }
    }

    private static void restoreRoad(@NotNull World world, @NotNull RoadSlice slice,
                                     int plotHeight, @NotNull Material roadMaterial) {
        for (int x = slice.minX(); x <= slice.maxX(); x++) {
            for (int z = slice.minZ(); z <= slice.maxZ(); z++) {
                for (int y = 1; y < plotHeight; y++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE, false);
                }
                world.getBlockAt(x, plotHeight, z).setType(roadMaterial, false);
            }
        }
    }

    /**
     * Clears the wall stripes that A and B placed along the edge that faces
     * the other plot. Stripe height comes from the world config.
     */
    private static void clearWallsBetween(@NotNull World world, @NotNull Plot a, @NotNull Plot b,
                                           int plotSize, int roadWidth, int plotHeight,
                                           int wallHeight) {
        applyWallsBetween(world, a, b, plotSize, roadWidth, plotHeight, wallHeight, Material.AIR);
    }

    private static void restoreWallsBetween(@NotNull World world, @NotNull Plot a, @NotNull Plot b,
                                             int plotSize, int roadWidth, int plotHeight,
                                             int wallHeight, @NotNull Material wallMaterial) {
        applyWallsBetween(world, a, b, plotSize, roadWidth, plotHeight, wallHeight, wallMaterial);
    }

    private static void applyWallsBetween(@NotNull World world, @NotNull Plot a, @NotNull Plot b,
                                           int plotSize, int roadWidth, int plotHeight,
                                           int wallHeight, @NotNull Material material) {
        int interval = plotSize + roadWidth;
        // Edge column on A facing B + edge column on B facing A.
        int aEdgeX, aMinZ, aMaxZ, bEdgeX, bMinZ, bMaxZ;
        int aEdgeZ, bEdgeZ, aMinX, aMaxX, bMinX, bMaxX;

        if (a.getGridX() == b.getGridX()) {
            // North-south: edge is along Z axis.
            int aZ0 = a.getGridZ() * interval;
            int bZ0 = b.getGridZ() * interval;
            aEdgeZ = a.getGridZ() < b.getGridZ() ? aZ0 + plotSize - 1 : aZ0;
            bEdgeZ = b.getGridZ() < a.getGridZ() ? bZ0 + plotSize - 1 : bZ0;
            aMinX = a.getGridX() * interval;
            aMaxX = aMinX + plotSize - 1;
            bMinX = b.getGridX() * interval;
            bMaxX = bMinX + plotSize - 1;
            applyWallStripeZ(world, aEdgeZ, aMinX, aMaxX, plotHeight, wallHeight, material);
            applyWallStripeZ(world, bEdgeZ, bMinX, bMaxX, plotHeight, wallHeight, material);
        } else {
            // East-west: edge is along X axis.
            int aX0 = a.getGridX() * interval;
            int bX0 = b.getGridX() * interval;
            aEdgeX = a.getGridX() < b.getGridX() ? aX0 + plotSize - 1 : aX0;
            bEdgeX = b.getGridX() < a.getGridX() ? bX0 + plotSize - 1 : bX0;
            aMinZ = a.getGridZ() * interval;
            aMaxZ = aMinZ + plotSize - 1;
            bMinZ = b.getGridZ() * interval;
            bMaxZ = bMinZ + plotSize - 1;
            applyWallStripeX(world, aEdgeX, aMinZ, aMaxZ, plotHeight, wallHeight, material);
            applyWallStripeX(world, bEdgeX, bMinZ, bMaxZ, plotHeight, wallHeight, material);
        }
    }

    private static void applyWallStripeX(@NotNull World world, int x, int minZ, int maxZ,
                                          int plotHeight, int wallHeight, @NotNull Material material) {
        for (int z = minZ; z <= maxZ; z++) {
            for (int dy = 1; dy <= wallHeight; dy++) {
                world.getBlockAt(x, plotHeight + dy, z).setType(material, false);
            }
        }
    }

    private static void applyWallStripeZ(@NotNull World world, int z, int minX, int maxX,
                                          int plotHeight, int wallHeight, @NotNull Material material) {
        for (int x = minX; x <= maxX; x++) {
            for (int dy = 1; dy <= wallHeight; dy++) {
                world.getBlockAt(x, plotHeight + dy, z).setType(material, false);
            }
        }
    }
}
