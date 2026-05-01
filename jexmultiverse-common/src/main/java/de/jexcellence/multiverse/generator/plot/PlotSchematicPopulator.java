package de.jexcellence.multiverse.generator.plot;

import de.jexcellence.multiverse.service.SchematicService;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Populator that pastes a schematic at the NW corner of every plot whose
 * anchor falls within the populated chunk. The schematic is loaded once via
 * {@link SchematicService} (Bukkit Structure or WorldEdit clipboard); the
 * format-specific paste path is selected by the {@code PlacedSchematic}
 * returned from the service.
 *
 * <p>Per-paste offsets ({@code schematic-offset-x/y/z} in {@code config.yml})
 * are applied relative to the plot's NW corner ({@code plotHeight} for Y).
 *
 * @author JExcellence
 * @since 3.1.0
 */
public class PlotSchematicPopulator extends BlockPopulator {

    private final SchematicService schematics;
    private final String schematicName;
    private final int plotSize;
    private final int roadWidth;
    private final int plotHeight;
    private final int totalInterval;

    public PlotSchematicPopulator(@NotNull SchematicService schematics,
                                  @NotNull String schematicName,
                                  int plotSize, int roadWidth, int plotHeight) {
        this.schematics = schematics;
        this.schematicName = schematicName;
        this.plotSize = plotSize;
        this.roadWidth = roadWidth;
        this.plotHeight = plotHeight;
        this.totalInterval = plotSize + roadWidth;
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random,
                         int chunkX, int chunkZ, @NotNull LimitedRegion region) {
        var schematic = schematics.load(schematicName).orElse(null);
        if (schematic == null) return;

        int chunkMinX = chunkX << 4;
        int chunkMinZ = chunkZ << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

        for (int gridX = Math.floorDiv(chunkMinX, totalInterval);
             gridX <= Math.floorDiv(chunkMaxX, totalInterval); gridX++) {
            for (int gridZ = Math.floorDiv(chunkMinZ, totalInterval);
                 gridZ <= Math.floorDiv(chunkMaxZ, totalInterval); gridZ++) {
                int anchorX = gridX * totalInterval;
                int anchorZ = gridZ * totalInterval;
                if (anchorX < chunkMinX || anchorX > chunkMaxX) continue;
                if (anchorZ < chunkMinZ || anchorZ > chunkMaxZ) continue;
                schematic.tryPlace(region,
                        anchorX + schematics.offsetX(),
                        plotHeight + schematics.offsetY(),
                        anchorZ + schematics.offsetZ());
            }
        }
    }

    /**
     * Manually pastes the structure for a single grid cell at runtime. Used by
     * the {@code /mv applyschematic} command for retroactive application to
     * already-generated chunks. Must be called on the main thread.
     */
    public static void placeManually(@NotNull SchematicService schematics,
                                     @NotNull String schematicName,
                                     @NotNull World world,
                                     int gridX, int gridZ,
                                     int plotSize, int roadWidth, int plotHeight,
                                     @NotNull Random random) {
        schematics.load(schematicName).ifPresent(schematic -> {
            int totalInterval = plotSize + roadWidth;
            schematic.place(world,
                    gridX * totalInterval + schematics.offsetX(),
                    plotHeight + schematics.offsetY(),
                    gridZ * totalInterval + schematics.offsetZ());
        });
    }
}
