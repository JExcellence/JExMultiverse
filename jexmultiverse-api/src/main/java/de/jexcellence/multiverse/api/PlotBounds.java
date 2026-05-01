package de.jexcellence.multiverse.api;

import org.jetbrains.annotations.NotNull;

/**
 * World-space bounds of a single plot in a {@link MVWorldType#PLOT} world.
 *
 * <p>All coordinates are inclusive block coordinates. The plot interior is
 * the AABB {@code [minX, surfaceY, minZ] .. [maxX, surfaceY + plotHeight, maxZ]}.
 *
 * @param world     the JExMultiverse world identifier
 * @param gridX     plot grid X coordinate
 * @param gridZ     plot grid Z coordinate
 * @param minX      inclusive minimum world X
 * @param minZ      inclusive minimum world Z
 * @param maxX      inclusive maximum world X
 * @param maxZ      inclusive maximum world Z
 * @param surfaceY  world Y coordinate of the plot surface
 *
 * @author JExcellence
 * @since 3.1.0
 */
public record PlotBounds(
        @NotNull String world,
        int gridX, int gridZ,
        int minX, int minZ,
        int maxX, int maxZ,
        int surfaceY
) {

    /** Plot edge length in blocks ({@code maxX - minX + 1}). */
    public int size() {
        return maxX - minX + 1;
    }

    /** Center X coordinate (rounded down for even sizes). */
    public int centerX() {
        return minX + size() / 2;
    }

    /** Center Z coordinate (rounded down for even sizes). */
    public int centerZ() {
        return minZ + size() / 2;
    }
}
