package de.jexcellence.multiverse.generator.plot;

import de.jexcellence.multiverse.service.SchematicService;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
    private final int wallHeight;
    private final Material roadMaterial;
    private final Material wallMaterial;
    private final List<PlotLayer> layers;
    private final int totalInterval;
    private final SchematicService schematics;
    private final String schematicName;

    public PlotChunkGenerator(int plotSize, int roadWidth, int plotHeight,
                              @NotNull Material roadMaterial, @NotNull Material wallMaterial,
                              @NotNull List<PlotLayer> layers) {
        this(plotSize, roadWidth, plotHeight, 1, roadMaterial, wallMaterial, layers, null, null);
    }

    public PlotChunkGenerator(int plotSize, int roadWidth, int plotHeight, int wallHeight,
                              @NotNull Material roadMaterial, @NotNull Material wallMaterial,
                              @NotNull List<PlotLayer> layers,
                              @Nullable SchematicService schematics,
                              @Nullable String schematicName) {
        this.plotSize = plotSize;
        this.roadWidth = roadWidth;
        this.plotHeight = plotHeight;
        this.wallHeight = wallHeight;
        this.roadMaterial = roadMaterial;
        this.wallMaterial = wallMaterial;
        this.layers = layers;
        this.totalInterval = plotSize + roadWidth;
        this.schematics = schematics;
        this.schematicName = schematicName;
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                generateColumn(chunkData, x, z, startX + x, startZ + z);
            }
        }
    }

    private void generateColumn(@NotNull ChunkData chunkData, int x, int z, int worldX, int worldZ) {
        int modX = Math.floorMod(worldX, totalInterval);
        int modZ = Math.floorMod(worldZ, totalInterval);

        boolean isRoadX = modX >= plotSize;
        boolean isRoadZ = modZ >= plotSize;

        chunkData.setBlock(x, 0, z, Material.BEDROCK);

        if (isRoadX || isRoadZ) {
            generateRoadColumn(chunkData, x, z, modX, modZ, isRoadX, isRoadZ);
        } else {
            generatePlotColumn(chunkData, x, z);
        }
    }

    private void generateRoadColumn(@NotNull ChunkData chunkData, int x, int z,
                                     int modX, int modZ, boolean isRoadX, boolean isRoadZ) {
        for (int y = 0; y < plotHeight; y++) {
            chunkData.setBlock(x, y, z, Material.STONE);
        }
        chunkData.setBlock(x, plotHeight, z, roadMaterial);

        if (wallHeight > 0 && isWallEdge(modX, modZ, isRoadX, isRoadZ)) {
            for (int dy = 1; dy <= wallHeight; dy++) {
                chunkData.setBlock(x, plotHeight + dy, z, wallMaterial);
            }
        }
    }

    private boolean isWallEdge(int modX, int modZ, boolean isRoadX, boolean isRoadZ) {
        boolean isRoadEdgeX = isRoadX && (modX == plotSize || modX == totalInterval - 1);
        boolean isRoadEdgeZ = isRoadZ && (modZ == plotSize || modZ == totalInterval - 1);
        return isRoadEdgeX || isRoadEdgeZ;
    }

    private void generatePlotColumn(@NotNull ChunkData chunkData, int x, int z) {
        for (PlotLayer layer : layers) {
            for (int y = layer.minY(); y <= layer.maxY() && y <= plotHeight; y++) {
                chunkData.setBlock(x, y, z, layer.material());
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
        if (schematics != null && schematicName != null && !schematicName.isBlank()) {
            populators.add(new PlotSchematicPopulator(
                    schematics, schematicName, plotSize, roadWidth, plotHeight));
        }
        return populators;
    }
}
