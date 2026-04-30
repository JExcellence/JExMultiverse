package de.jexcellence.multiverse.generator.plot;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Block populator that places border walls around plot boundaries after chunk generation.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class PlotBlockPopulator extends BlockPopulator {

    private final int plotSize;
    private final int roadWidth;
    private final int plotHeight;
    private final Material wallMaterial;
    private final int totalInterval;

    public PlotBlockPopulator(int plotSize, int roadWidth, int plotHeight, @NotNull Material wallMaterial) {
        this.plotSize = plotSize;
        this.roadWidth = roadWidth;
        this.plotHeight = plotHeight;
        this.wallMaterial = wallMaterial;
        this.totalInterval = plotSize + roadWidth;
    }

    @Override
    public void populate(@NotNull World world, @NotNull Random random, @NotNull Chunk chunk) {
        // Populator can add additional decorative elements to plot borders if needed
    }
}
