package de.jexcellence.multiverse.generator.plot;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a single layer in a plot world, defined by material and height range.
 *
 * @param material the block material for this layer
 * @param minY     the inclusive minimum Y level
 * @param maxY     the inclusive maximum Y level
 * @author JExcellence
 * @since 3.0.0
 */
public record PlotLayer(
        @NotNull Material material,
        int minY,
        int maxY
) {
}
