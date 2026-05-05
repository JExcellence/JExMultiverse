package de.jexcellence.multiverse.service;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * {@link PlacedSchematic} backed by Bukkit's native {@link Structure} API.
 * Loaded from {@code .nbt} files saved by structure blocks.
 */
public final class BukkitStructureSchematic implements PlacedSchematic {

    private static final Random RANDOM = new Random();

    private final Structure structure;

    public BukkitStructureSchematic(@NotNull Structure structure) {
        this.structure = structure;
    }

    @Override
    public void place(@NotNull World world, int x, int y, int z) {
        structure.place(new Location(world, x, y, z), true,
                StructureRotation.NONE, Mirror.NONE,
                0, 1.0f, RANDOM);
    }

    @Override
    public boolean tryPlace(@NotNull LimitedRegion region, int x, int y, int z) {
        try {
            structure.place(region,
                    new BlockVector(x, y, z),
                    true,
                    StructureRotation.NONE, Mirror.NONE,
                    0, 1.0f, RANDOM);
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        }
    }
}
