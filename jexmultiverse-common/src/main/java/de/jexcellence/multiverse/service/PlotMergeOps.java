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

    /**
     * Applies the visual merge between two adjacent plots. Fills the road
     * slice with plot terrain AND clears the entire wall layer above the
     * slice — covers both walls (now on road-edge columns) in one pass.
     */
    public static void applyMerge(@NotNull World world, @NotNull Plot a, @NotNull Plot b,
                                   int plotSize, int roadWidth, @NotNull PlotWorldConfig config) {
        if (!areAdjacent(a, b)) return;
        var slice = roadSlice(a, b, plotSize, roadWidth);
        fillPlotTerrain(world, slice, config.plotHeight(), config.wallHeight(), config.layers());
    }

    /**
     * Applies the visual unmerge: restores road surface + the two wall
     * stripes on the slice's outer columns (the cells immediately adjacent
     * to each plot).
     */
    public static void applyUnmerge(@NotNull World world, @NotNull Plot a, @NotNull Plot b,
                                     int plotSize, int roadWidth,
                                     @NotNull PlotWorldConfig config,
                                     @NotNull Material claimedWallMaterial) {
        if (!areAdjacent(a, b)) return;
        var slice = roadSlice(a, b, plotSize, roadWidth);
        restoreRoad(world, slice, config.plotHeight(), config.roadMaterial());
        restoreSliceEdgeWalls(world, slice, config.plotHeight(), config.wallHeight(),
                claimedWallMaterial, a.getGridX() == b.getGridX());
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
                                         int plotHeight, int wallHeight,
                                         @NotNull List<PlotLayer> layers) {
        for (int x = slice.minX(); x <= slice.maxX(); x++) {
            for (int z = slice.minZ(); z <= slice.maxZ(); z++) {
                // Clear the road material at plotHeight AND any walls that
                // sat on the slice's edge columns above it.
                for (int dy = 0; dy <= wallHeight; dy++) {
                    world.getBlockAt(x, plotHeight + dy, z).setType(Material.AIR, false);
                }
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

    /**
     * Restores wall stripes on the two outer edges of the road slice — the
     * columns adjacent to each plot. Replaces the old plot-edge-column logic
     * now that walls live on road-edge columns.
     */
    private static void restoreSliceEdgeWalls(@NotNull World world, @NotNull RoadSlice slice,
                                                int plotHeight, int wallHeight,
                                                @NotNull Material material,
                                                boolean sliceIsNorthSouth) {
        if (wallHeight <= 0) return;
        if (sliceIsNorthSouth) {
            // Slice runs along Z axis; its outer Z columns are the walls.
            for (int x = slice.minX(); x <= slice.maxX(); x++) {
                for (int dy = 1; dy <= wallHeight; dy++) {
                    world.getBlockAt(x, plotHeight + dy, slice.minZ()).setType(material, false);
                    world.getBlockAt(x, plotHeight + dy, slice.maxZ()).setType(material, false);
                }
            }
        } else {
            // Slice runs along X axis; its outer X columns are the walls.
            for (int z = slice.minZ(); z <= slice.maxZ(); z++) {
                for (int dy = 1; dy <= wallHeight; dy++) {
                    world.getBlockAt(slice.minX(), plotHeight + dy, z).setType(material, false);
                    world.getBlockAt(slice.maxX(), plotHeight + dy, z).setType(material, false);
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

}
