package de.jexcellence.multiverse.generator.plot;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * Biome provider that returns {@link Biome#PLAINS} for every block in a plot world.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class PlotBiomeProvider extends BiomeProvider {

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        return Biome.PLAINS;
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return Collections.singletonList(Biome.PLAINS);
    }
}
