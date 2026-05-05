package de.jexcellence.multiverse.api;

import org.jetbrains.annotations.NotNull;

/**
 * Grid coordinates of a single plot in a {@link MVWorldType#PLOT} world.
 *
 * <p>Plots are arranged on a regular {@code (plotSize + roadWidth)} grid
 * starting at world coordinate {@code (0, 0)}. Plot {@code (0, 0)} occupies
 * world coordinates {@code [0 .. plotSize - 1]} on both X and Z; the road
 * between plots {@code (0, 0)} and {@code (1, 0)} occupies
 * {@code [plotSize .. plotSize + roadWidth - 1]}.
 *
 * <p>This record is exposed by {@link MultiverseProvider#plotAt} so external
 * plugins (claim systems, plot-merging tools, etc.) can identify plot cells
 * without having to know the underlying generation parameters.
 *
 * @param world  the JExMultiverse world identifier
 * @param gridX  plot grid X coordinate
 * @param gridZ  plot grid Z coordinate
 *
 * @author JExcellence
 * @since 3.1.0
 */
public record PlotCoord(@NotNull String world, int gridX, int gridZ) {
}
