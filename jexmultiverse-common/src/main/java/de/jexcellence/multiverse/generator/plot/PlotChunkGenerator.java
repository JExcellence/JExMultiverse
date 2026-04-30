package de.jexcellence.multiverse.generator.plot;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Chunk generator for plot worlds. Creates flat terrain divided into plots
 * separated by roads and optional border walls.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class PlotChunkGenerator extends ChunkGenerator {

    private final int plotSize;
    private final int roadWidth;
    private final int plotHeight;
    private final Material roadMaterial;
    private final Material wallMaterial;
    private final List<PlotLayer> layers;
    private final int totalInterval;

    public PlotChunkGenerator(int plotSize, int roadWidth, int plotHeight,
                              @NotNull Material roadMaterial, @NotNull Material wallMaterial,
                              @NotNull List<PlotLayer> layers) {
        this.plotSize = plotSize;
        this.roadWidth = roadWidth;
        this.plotHeight = plotHeight;
        this.roadMaterial = roadMaterial;
        this.wallMaterial = wallMaterial;
        this.layers = layers;
        this.totalInterval = plotSize + roadWidth;
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = startX + x;
                int worldZ = startZ + z;

                int modX = Math.floorMod(worldX, totalInterval);
                int modZ = Math.floorMod(worldZ, totalInterval);

                boolean isRoadX = modX >= plotSize;
                boolean isRoadZ = modZ >= plotSize;
                boolean isBorderX = modX == plotSize || modX == totalInterval - 1;
                boolean isBorderZ = modZ == plotSize || modZ == totalInterval - 1;

                if (isRoadX || isRoadZ) {
                    // Road or border
                    for (int y = 0; y <= plotHeight; y++) {
                        if (y == plotHeight && (isBorderX || isBorderZ)) {
                            chunkData.setBlock(x, y, z, wallMaterial);
                        } else if (y == plotHeight) {
                            chunkData.setBlock(x, y, z, roadMaterial);
                        } else {
                            chunkData.setBlock(x, y, z, Material.STONE);
                        }
                    }
                } else {
                    // Plot area
                    for (PlotLayer layer : layers) {
                        for (int y = layer.minY(); y <= layer.maxY() && y <= plotHeight; y++) {
                            chunkData.setBlock(x, y, z, layer.material());
                        }
                    }
                }

                // Bedrock floor
                chunkData.setBlock(x, 0, z, Material.BEDROCK);
            }
        }
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        List<BlockPopulator> populators = new ArrayList<>();
        populators.add(new PlotBlockPopulator(plotSize, roadWidth, plotHeight, wallMaterial));
        return populators;
    }
}
