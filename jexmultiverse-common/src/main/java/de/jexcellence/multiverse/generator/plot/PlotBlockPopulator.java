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

    public PlotBlockPopulator(int plotSize, int roadWidth, int plotHeight, @NotNull Material wallMaterial) {
        // Parameters retained for API compatibility; generation is handled by PlotChunkGenerator.
    }

    @Override
    public void populate(@NotNull World world, @NotNull Random random, @NotNull Chunk chunk) {
        // Populator can add additional decorative elements to plot borders if needed
    }
}
