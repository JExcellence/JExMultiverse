package de.jexcellence.multiverse.generator.plot;

import de.jexcellence.multiverse.service.SchematicService;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Populator that pastes a Bukkit {@link org.bukkit.structure.Structure} at the
 * NW corner of every plot whose center falls within the populated chunk.
 *
 * <p>The structure is loaded once via {@link SchematicService} and reused for
 * every paste, so populator throughput is unchanged after the first lookup.
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
        var structure = schematics.load(schematicName).orElse(null);
        if (structure == null) return;

        int chunkMinX = chunkX << 4;
        int chunkMinZ = chunkZ << 4;
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

        // Each plot's "anchor" is its NW interior corner: (gridX*interval, gridZ*interval).
        // Paste is queued only when the anchor falls within this chunk so each
        // plot is built exactly once across the chunks it spans.
        for (int gridX = Math.floorDiv(chunkMinX, totalInterval);
             gridX <= Math.floorDiv(chunkMaxX, totalInterval); gridX++) {
            for (int gridZ = Math.floorDiv(chunkMinZ, totalInterval);
                 gridZ <= Math.floorDiv(chunkMaxZ, totalInterval); gridZ++) {
                int anchorX = gridX * totalInterval;
                int anchorZ = gridZ * totalInterval;
                if (anchorX < chunkMinX || anchorX > chunkMaxX) continue;
                if (anchorZ < chunkMinZ || anchorZ > chunkMaxZ) continue;
                place(structure, region, anchorX, plotHeight + 1, anchorZ, random);
            }
        }
    }

    private void place(@NotNull org.bukkit.structure.Structure structure,
                       @NotNull LimitedRegion region,
                       int x, int y, int z, @NotNull Random random) {
        // LimitedRegion constrains writes to the populated chunk's footprint
        // plus an 8-block buffer, which is fine for plots (each plot is
        // wholly contained within its single anchor chunk's region).
        try {
            structure.place(region,
                    new org.bukkit.util.BlockVector(x, y, z),
                    true,
                    StructureRotation.NONE, Mirror.NONE,
                    0, 1.0f, random);
        } catch (UnsupportedOperationException e) {
            // Some Paper builds reject Structure.place into LimitedRegion;
            // /mv applyschematic remains as the fallback.
        }
    }

    /**
     * Manually pastes the structure for a single grid cell at runtime. Used by
     * the {@code /mv plot apply-schematic} command for retroactive application
     * to already-generated chunks. Must be called on the main thread.
     */
    public static void placeManually(@NotNull SchematicService schematics,
                                     @NotNull String schematicName,
                                     @NotNull World world,
                                     int gridX, int gridZ,
                                     int plotSize, int roadWidth, int plotHeight,
                                     @NotNull Random random) {
        schematics.load(schematicName).ifPresent(structure -> {
            int totalInterval = plotSize + roadWidth;
            int x = gridX * totalInterval;
            int z = gridZ * totalInterval;
            structure.place(new Location(world, x, plotHeight + 1, z),
                    true, StructureRotation.NONE, Mirror.NONE,
                    0, 1.0f, random);
        });
    }
}
